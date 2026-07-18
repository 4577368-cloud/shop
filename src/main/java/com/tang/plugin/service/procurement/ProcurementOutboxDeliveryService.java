package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementPullResult;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Outbox delivery for procurement tasks (ack-based at-least-once).
 * <p>
 * pull is read-only: it returns deliverable tasks and stamps an observational pulled marker
 * (delivery_attempts += 1, last_pulled_at = now); it never claims and never changes delivery_status.
 * ack is the ONLY entry that moves PENDING_DELIVERY -> DELIVERED. delivery_status is orthogonal to
 * task_status. P1 has no claim lease, no timeout reclaim, no auto-retry, no requeue.
 */
@Slf4j
@Service
public class ProcurementOutboxDeliveryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    @Resource
    private ThirdPlatformProcurementTaskRepository thirdPlatformProcurementTaskRepository;
    @Resource
    private TxManger txManger;

    /**
     * Pull up to {@code limit} deliverable tasks (del_flag=0, PENDING, PENDING_DELIVERY) ordered by
     * id ASC, then mark the pulled ids. Returns the read snapshot (before the pulled-marker update).
     */
    public ProcurementPullResult pull(String shopName, Integer limit) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("pull requires shopName");
        }
        int effectiveLimit = normalizeLimit(limit);

        List<ThirdPlatformProcurementTask> tasks = txManger.run(() -> {
            List<ThirdPlatformProcurementTask> deliverable =
                    thirdPlatformProcurementTaskRepository.listDeliverable(shopName, effectiveLimit);
            if (!deliverable.isEmpty()) {
                List<Long> ids = deliverable.stream().map(ThirdPlatformProcurementTask::getId).toList();
                thirdPlatformProcurementTaskRepository.markPulled(ids);
            }
            return deliverable;
        });

        log.info("Procurement outbox pull shopName={} limit={} pulled={}",
                shopName, effectiveLimit, tasks.size());
        return new ProcurementPullResult()
                .setShopName(shopName)
                .setLimit(effectiveLimit)
                .setPulled(tasks.size())
                .setTasks(tasks);
    }

    /**
     * Ack by (shopName, lineId) — the primary entry, consistent with the task idempotency key.
     */
    public ProcurementAckResult ackByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("ackByLine requires shopName and lineId");
        }
        ThirdPlatformProcurementTask task = thirdPlatformProcurementTaskRepository.findByLine(shopName, lineId)
                .orElseThrow(() -> new CustomException("Procurement task not found, shopName=" + shopName
                        + ", lineId=" + lineId));
        return ack(task);
    }

    /**
     * Ack by taskId — supplementary entry for consumers and internal debugging. Rejects soft-deleted.
     */
    public ProcurementAckResult ackByTaskId(Long taskId) {
        if (taskId == null) {
            throw new CustomException("ackByTaskId requires taskId");
        }
        ThirdPlatformProcurementTask task = thirdPlatformProcurementTaskRepository.findById(taskId)
                .orElseThrow(() -> new CustomException("Procurement task not found, taskId=" + taskId));
        if (task.getDelFlag() != null && task.getDelFlag() != 0) {
            throw new CustomException("Procurement task is soft-deleted, cannot ack, taskId=" + taskId);
        }
        return ack(task);
    }

    /**
     * Shared ack logic. Rejects CANCELLED. DELIVERED -> idempotent success (delivered_at kept).
     * PENDING_DELIVERY -> DELIVERED via a guarded update; a lost race resolves as idempotent success.
     */
    private ProcurementAckResult ack(ThirdPlatformProcurementTask task) {
        if (task.getTaskStatus() == ProcurementTaskStatus.CANCELLED) {
            throw new CustomException("Cannot ack a CANCELLED task, taskId=" + task.getId()
                    + ", lineId=" + task.getLineId());
        }

        ProcurementAckResult result = new ProcurementAckResult()
                .setTaskId(task.getId())
                .setShopName(task.getShopName())
                .setLineId(task.getLineId());

        if (task.getDeliveryStatus() == ProcurementTaskDeliveryStatus.DELIVERED) {
            log.info("Procurement outbox ack idempotent (already DELIVERED) taskId={} shopName={} lineId={}",
                    task.getId(), task.getShopName(), task.getLineId());
            return result.setOutcome(ProcurementAckResult.Outcome.ALREADY_DELIVERED);
        }

        int updated = txManger.run(() -> thirdPlatformProcurementTaskRepository.markDelivered(task.getId()));
        if (updated > 0) {
            log.info("Procurement outbox ack DELIVERED taskId={} shopName={} lineId={}",
                    task.getId(), task.getShopName(), task.getLineId());
            return result.setOutcome(ProcurementAckResult.Outcome.DELIVERED);
        }
        log.info("Procurement outbox ack idempotent (concurrent DELIVERED) taskId={} shopName={} lineId={}",
                task.getId(), task.getShopName(), task.getLineId());
        return result.setOutcome(ProcurementAckResult.Outcome.ALREADY_DELIVERED);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
