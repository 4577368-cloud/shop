package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.procurement.ProcurementAnomalyEntry;
import com.tang.plugin.domain.dto.procurement.ProcurementChainSummary;
import com.tang.plugin.domain.dto.procurement.ProcurementChainView;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.domain.dto.procurement.ProcurementExecutionView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementChainAnomaly;
import com.tang.plugin.enums.procurement.ProcurementChainAnomaly.Severity;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementConsumptionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementExecutionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only operations/audit layer over the task -> outbox -> consumer-receipt chain.
 * Assembles chain views anchored on the procurement task, derives anomaly codes at query time,
 * and produces triage summaries. Never writes; never changes delivery_status / consumption_status.
 */
@Slf4j
@Service
public class ProcurementOpsQueryService {

    private static final int CHAIN_DEFAULT_LIMIT = 50;
    private static final int CHAIN_MAX_LIMIT = 200;
    private static final int ANOMALY_DEFAULT_LIMIT = 50;
    private static final int ANOMALY_MAX_LIMIT = 200;
    /** Bounded scan window for anomaly listing and summary anomaly counts. */
    private static final int SCAN_CAP = 1000;
    private static final long DEFAULT_STALE_MINUTES = 60L;

    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private ThirdPlatformProcurementConsumptionRepository consumptionRepository;
    @Resource
    private ThirdPlatformProcurementExecutionRepository executionRepository;

    public ProcurementChainView chainByTask(String shopName, Long taskId) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            throw new CustomException("chainByTask requires shopName and taskId");
        }
        ThirdPlatformProcurementTask task = taskRepository.findById(taskId)
                .filter(t -> (t.getDelFlag() == null || t.getDelFlag() == 0))
                .filter(t -> StringUtils.equals(shopName, t.getShopName()))
                .orElseThrow(() -> new CustomException("Procurement task not found for shopName=" + shopName
                        + ", taskId=" + taskId));
        List<ProcurementConsumptionView> receipts = consumptionRepository.listViewByTask(shopName, taskId);
        ThirdPlatformProcurementExecution execution = executionRepository.findByTask(taskId).orElse(null);
        return buildChainView(task, receipts, execution, defaultStaleThreshold());
    }

    public ProcurementChainView chainByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("chainByLine requires shopName and lineId");
        }
        ThirdPlatformProcurementTask task = taskRepository.findByLine(shopName, lineId)
                .orElseThrow(() -> new CustomException("Procurement task not found for shopName=" + shopName
                        + ", lineId=" + lineId));
        List<ProcurementConsumptionView> receipts = consumptionRepository.listViewByTask(shopName, task.getId());
        ThirdPlatformProcurementExecution execution = executionRepository.findByTask(task.getId()).orElse(null);
        return buildChainView(task, receipts, execution, defaultStaleThreshold());
    }

    public List<ProcurementChainView> chainByShop(String shopName, ProcurementTaskStatus taskStatus,
                                                  ProcurementTaskDeliveryStatus deliveryStatus, Integer limit) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("chainByShop requires shopName");
        }
        int effectiveLimit = normalizeLimit(limit, CHAIN_DEFAULT_LIMIT, CHAIN_MAX_LIMIT);
        List<ThirdPlatformProcurementTask> tasks =
                taskRepository.listByShop(shopName, taskStatus, deliveryStatus, effectiveLimit);
        return assembleChainViews(tasks, defaultStaleThreshold());
    }

    /**
     * List actionable anomalies over a bounded scan window (SCAN_CAP). Default severities are
     * WARN and ERROR; INFO is included only when {@code includeInfo} is true. Optional {@code type}
     * narrows to one anomaly code. staleMinutes defaults to 60 when null/non-positive.
     */
    public List<ProcurementAnomalyEntry> anomalies(String shopName, ProcurementChainAnomaly type,
                                                   Long staleMinutes, boolean includeInfo, Integer limit) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("anomalies requires shopName");
        }
        int effectiveLimit = normalizeLimit(limit, ANOMALY_DEFAULT_LIMIT, ANOMALY_MAX_LIMIT);
        Instant threshold = staleThreshold(staleMinutes);

        List<ProcurementChainView> views = assembleChainViews(
                taskRepository.listByShop(shopName, null, null, SCAN_CAP), threshold);

        List<ProcurementAnomalyEntry> entries = new ArrayList<>();
        for (ProcurementChainView view : views) {
            for (ProcurementChainAnomaly anomaly : view.getAnomalies()) {
                if (type != null && anomaly != type) {
                    continue;
                }
                if (!includeInfo && anomaly.getSeverity() == Severity.INFO) {
                    continue;
                }
                entries.add(toAnomalyEntry(view, anomaly));
                if (entries.size() >= effectiveLimit) {
                    return entries;
                }
            }
        }
        return entries;
    }

    /**
     * Triage overview. Status counts are exact (SQL GROUP BY); anomalyCounts are derived over the
     * bounded scan window (SCAN_CAP) using the default stale threshold.
     */
    public ProcurementChainSummary summary(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("summary requires shopName");
        }
        Map<String, Long> taskStatusCounts = withEnumKeys(
                taskRepository.countGroupByTaskStatus(shopName), enumNames(ProcurementTaskStatus.values()));
        Map<String, Long> deliveryStatusCounts = withEnumKeys(
                taskRepository.countGroupByDeliveryStatus(shopName),
                enumNames(ProcurementTaskDeliveryStatus.values()));
        Map<String, Long> consumptionStatusCounts = withEnumKeys(
                consumptionRepository.countGroupByConsumptionStatus(shopName),
                enumNames(ProcurementConsumptionStatus.values()));

        Map<String, Long> anomalyCounts = new LinkedHashMap<>();
        for (ProcurementChainAnomaly anomaly : ProcurementChainAnomaly.values()) {
            anomalyCounts.put(anomaly.name(), 0L);
        }
        List<ProcurementChainView> views = assembleChainViews(
                taskRepository.listByShop(shopName, null, null, SCAN_CAP), defaultStaleThreshold());
        for (ProcurementChainView view : views) {
            for (ProcurementChainAnomaly anomaly : view.getAnomalies()) {
                anomalyCounts.merge(anomaly.name(), 1L, Long::sum);
            }
        }

        return new ProcurementChainSummary()
                .setShopName(shopName)
                .setTaskStatusCounts(taskStatusCounts)
                .setDeliveryStatusCounts(deliveryStatusCounts)
                .setConsumptionStatusCounts(consumptionStatusCounts)
                .setAnomalyCounts(anomalyCounts);
    }

    private List<ProcurementChainView> assembleChainViews(List<ThirdPlatformProcurementTask> tasks,
                                                          Instant staleThreshold) {
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> taskIds = tasks.stream().map(ThirdPlatformProcurementTask::getId).toList();
        Map<Long, List<ProcurementConsumptionView>> receiptsByTask =
                consumptionRepository.listViewByTaskIds(taskIds).stream()
                        .collect(Collectors.groupingBy(ProcurementConsumptionView::getTaskId));
        Map<Long, ThirdPlatformProcurementExecution> executionByTask =
                executionRepository.listByTaskIds(taskIds).stream()
                        .collect(Collectors.toMap(ThirdPlatformProcurementExecution::getTaskId,
                                e -> e, (a, b) -> a));
        List<ProcurementChainView> views = new ArrayList<>(tasks.size());
        for (ThirdPlatformProcurementTask task : tasks) {
            views.add(buildChainView(task,
                    receiptsByTask.getOrDefault(task.getId(), Collections.emptyList()),
                    executionByTask.get(task.getId()), staleThreshold));
        }
        return views;
    }

    private ProcurementChainView buildChainView(ThirdPlatformProcurementTask task,
                                                List<ProcurementConsumptionView> receipts,
                                                ThirdPlatformProcurementExecution execution,
                                                Instant staleThreshold) {
        boolean hasAccepted = receipts.stream()
                .anyMatch(r -> r.getConsumptionStatus() == ProcurementConsumptionStatus.ACCEPTED);
        return new ProcurementChainView()
                .setTaskId(task.getId())
                .setShopName(task.getShopName())
                .setShopType(task.getShopType())
                .setOuterOrderId(task.getOuterOrderId())
                .setLineId(task.getLineId())
                .setTangbuyProductId(task.getTangbuyProductId())
                .setTangbuySkuId(task.getTangbuySkuId())
                .setQuantity(task.getQuantity())
                .setCurrency(task.getCurrency())
                .setUnitPrice(task.getUnitPrice())
                .setTaskStatus(task.getTaskStatus())
                .setDeliveryStatus(task.getDeliveryStatus())
                .setDeliveryAttempts(task.getDeliveryAttempts())
                .setLastPulledAt(task.getLastPulledAt())
                .setDeliveredAt(task.getDeliveredAt())
                .setCreatedAt(task.getCreatedAt())
                .setUpdatedAt(task.getUpdatedAt())
                .setReceipts(receipts)
                .setHasAcceptedReceipt(hasAccepted)
                .setExecution(toExecutionView(execution))
                .setHasExecution(execution != null)
                .setExecutionStatus(execution == null ? null : execution.getExecutionStatus())
                .setAnomalies(classify(task, receipts, execution, staleThreshold));
    }

    private ProcurementExecutionView toExecutionView(ThirdPlatformProcurementExecution execution) {
        if (execution == null) {
            return null;
        }
        return new ProcurementExecutionView()
                .setTaskId(execution.getTaskId())
                .setExecutionStatus(execution.getExecutionStatus())
                .setConsumerId(execution.getConsumerId())
                .setNote(execution.getNote())
                .setCreatedAt(execution.getCreatedAt())
                .setCompletedAt(execution.getCompletedAt());
    }

    private List<ProcurementChainAnomaly> classify(ThirdPlatformProcurementTask task,
                                                   List<ProcurementConsumptionView> receipts,
                                                   ThirdPlatformProcurementExecution execution,
                                                   Instant staleThreshold) {
        List<ProcurementChainAnomaly> anomalies = new ArrayList<>();

        ProcurementTaskStatus taskStatus = task.getTaskStatus();
        ProcurementTaskDeliveryStatus deliveryStatus = task.getDeliveryStatus();
        int attempts = task.getDeliveryAttempts() == null ? 0 : task.getDeliveryAttempts();

        boolean hasReceived = receipts.stream()
                .anyMatch(r -> r.getConsumptionStatus() == ProcurementConsumptionStatus.RECEIVED);
        boolean hasAccepted = receipts.stream()
                .anyMatch(r -> r.getConsumptionStatus() == ProcurementConsumptionStatus.ACCEPTED);
        long acceptedConsumers = receipts.stream()
                .filter(r -> r.getConsumptionStatus() == ProcurementConsumptionStatus.ACCEPTED)
                .map(ProcurementConsumptionView::getConsumerId)
                .distinct()
                .count();
        boolean anyReceipt = !receipts.isEmpty();

        boolean pending = taskStatus == ProcurementTaskStatus.PENDING;
        boolean pendingDelivery = deliveryStatus == ProcurementTaskDeliveryStatus.PENDING_DELIVERY;
        boolean delivered = deliveryStatus == ProcurementTaskDeliveryStatus.DELIVERED;

        if (pending && pendingDelivery && attempts == 0
                && isBefore(task.getCreatedAt(), staleThreshold)) {
            anomalies.add(ProcurementChainAnomaly.STALE_PENDING_UNPULLED);
        }
        if (pending && pendingDelivery && attempts >= 1
                && isBefore(task.getLastPulledAt(), staleThreshold)) {
            anomalies.add(ProcurementChainAnomaly.PULLED_NOT_DELIVERED);
        }
        if (hasReceived && !hasAccepted && pendingDelivery) {
            anomalies.add(ProcurementChainAnomaly.RECEIVED_NOT_ACCEPTED);
        }
        if (delivered && !hasAccepted) {
            anomalies.add(ProcurementChainAnomaly.DELIVERED_WITHOUT_ACCEPT);
        }
        if (hasAccepted && !delivered) {
            anomalies.add(ProcurementChainAnomaly.ACCEPTED_NOT_DELIVERED);
        }
        if (taskStatus == ProcurementTaskStatus.CANCELLED && anyReceipt) {
            anomalies.add(ProcurementChainAnomaly.CANCELLED_WITH_RECEIPTS);
        }
        if (acceptedConsumers > 1) {
            anomalies.add(ProcurementChainAnomaly.MULTI_CONSUMER_ACCEPTED);
        }

        if (execution != null) {
            ProcurementExecutionStatus executionStatus = execution.getExecutionStatus();
            if (executionStatus == ProcurementExecutionStatus.COMPLETED_STUB
                    && taskStatus == ProcurementTaskStatus.CANCELLED) {
                anomalies.add(ProcurementChainAnomaly.EXECUTION_COMPLETED_ON_CANCELLED);
            }
            if (executionStatus == ProcurementExecutionStatus.PENDING_EXECUTION
                    && isBefore(execution.getCreatedAt(), staleThreshold)) {
                anomalies.add(ProcurementChainAnomaly.EXECUTION_PENDING_STALE);
            }
        }
        return anomalies;
    }

    private ProcurementAnomalyEntry toAnomalyEntry(ProcurementChainView view, ProcurementChainAnomaly anomaly) {
        return new ProcurementAnomalyEntry()
                .setTaskId(view.getTaskId())
                .setShopName(view.getShopName())
                .setLineId(view.getLineId())
                .setAnomaly(anomaly)
                .setSeverity(anomaly.getSeverity())
                .setDescription(anomaly.getDescription())
                .setTaskStatus(view.getTaskStatus())
                .setDeliveryStatus(view.getDeliveryStatus())
                .setDeliveryAttempts(view.getDeliveryAttempts())
                .setCreatedAt(view.getCreatedAt())
                .setLastPulledAt(view.getLastPulledAt())
                .setDeliveredAt(view.getDeliveredAt());
    }

    private Instant defaultStaleThreshold() {
        return staleThreshold(DEFAULT_STALE_MINUTES);
    }

    private Instant staleThreshold(Long staleMinutes) {
        long minutes = (staleMinutes == null || staleMinutes <= 0) ? DEFAULT_STALE_MINUTES : staleMinutes;
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private static boolean isBefore(Instant value, Instant threshold) {
        return value != null && value.isBefore(threshold);
    }

    private static int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    private static List<String> enumNames(Enum<?>[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    private static Map<String, Long> withEnumKeys(Map<String, Long> counts, List<String> keys) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, counts.getOrDefault(key, 0L));
        }
        return result;
    }
}
