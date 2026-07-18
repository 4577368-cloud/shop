package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.procurement.ProcurementTaskCreateResult;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import com.tang.plugin.repository.ThirdPlatformOrderRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates minimal procurement tasks (outbox records) from BOUND order lines.
 * Explicit capability only — never auto-triggered by sync / persist / backfill. No procurement
 * execution logic. Idempotent by (shop_name, line_id); idempotent hit never refreshes snapshot.
 */
@Slf4j
@Service
public class ProcurementTaskService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;
    @Resource
    private ThirdPlatformOrderRepository thirdPlatformOrderRepository;
    @Resource
    private ThirdPlatformProcurementTaskRepository thirdPlatformProcurementTaskRepository;
    @Resource
    private TxManger txManger;

    /**
     * Create a task from a single order line. Rejects lines that do not meet preconditions.
     *
     * @return the persisted task id (existing or newly created).
     */
    public Long createFromOrderLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("createFromOrderLine requires shopName and lineId");
        }
        ThirdPlatformOrderLine line = thirdPlatformOrderLineRepository.findByLineId(shopName, lineId)
                .orElseThrow(() -> new CustomException("Order line not found, shopName=" + shopName
                        + ", lineId=" + lineId));
        if (line.getDelFlag() != null && line.getDelFlag() != 0) {
            throw new CustomException("Order line is soft-deleted, cannot create task, lineId=" + lineId);
        }
        if (!isEligible(line)) {
            throw new CustomException("Order line not eligible (must be BOUND with tangbuySkuId), lineId=" + lineId);
        }

        String currency = resolveCurrency(shopName, line.getOuterOrderId());
        ThirdPlatformProcurementTask task = buildTask(line, currency);
        Long id = txManger.run(() -> thirdPlatformProcurementTaskRepository.saveIfAbsent(task));
        log.info("Procurement task ensured id={} shopName={} outerOrderId={} lineId={} tangbuySkuId={}",
                id, shopName, line.getOuterOrderId(), lineId, line.getTangbuySkuId());
        return id;
    }

    /**
     * Create tasks for all eligible BOUND lines of an order.
     */
    public ProcurementTaskCreateResult createFromOrder(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            throw new CustomException("createFromOrder requires shopName and outerOrderId");
        }
        List<ThirdPlatformOrderLine> lines = thirdPlatformOrderLineRepository.listByOrder(shopName, outerOrderId);
        String currency = resolveCurrency(shopName, outerOrderId);

        ProcurementTaskCreateResult result = new ProcurementTaskCreateResult()
                .setShopName(shopName)
                .setOuterOrderId(outerOrderId);

        txManger.run(() -> {
            for (ThirdPlatformOrderLine line : lines) {
                if (!isEligible(line)) {
                    result.setSkippedNotBound(result.getSkippedNotBound() + 1);
                    continue;
                }
                result.setMatched(result.getMatched() + 1);
                boolean exists = thirdPlatformProcurementTaskRepository
                        .findByLine(shopName, line.getLineId()).isPresent();
                thirdPlatformProcurementTaskRepository.saveIfAbsent(buildTask(line, currency));
                if (exists) {
                    result.setSkippedExisting(result.getSkippedExisting() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                }
            }
        });
        log.info("Procurement tasks from order done shopName={} outerOrderId={} matched={} created={} "
                        + "skippedExisting={} skippedNotBound={}",
                shopName, outerOrderId, result.getMatched(), result.getCreated(),
                result.getSkippedExisting(), result.getSkippedNotBound());
        return result;
    }

    /**
     * Cancel a task: PENDING → CANCELLED; CANCELLED → CANCELLED is an idempotent no-op.
     */
    public void cancelByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("cancelByLine requires shopName and lineId");
        }
        ThirdPlatformProcurementTask task = thirdPlatformProcurementTaskRepository.findByLine(shopName, lineId)
                .orElseThrow(() -> new CustomException("Procurement task not found, shopName=" + shopName
                        + ", lineId=" + lineId));
        if (task.getTaskStatus() == ProcurementTaskStatus.CANCELLED) {
            log.info("Procurement task cancel idempotent no-op shopName={} lineId={}", shopName, lineId);
            return;
        }
        txManger.run(() -> thirdPlatformProcurementTaskRepository.updateStatus(
                task.getId(), ProcurementTaskStatus.CANCELLED));
        log.info("Procurement task cancelled shopName={} lineId={} taskId={}", shopName, lineId, task.getId());
    }

    private boolean isEligible(ThirdPlatformOrderLine line) {
        return line != null
                && (line.getDelFlag() == null || line.getDelFlag() == 0)
                && line.getBindingStatus() == OrderLineBindingStatus.BOUND
                && StringUtils.isNotBlank(line.getTangbuySkuId());
    }

    private ThirdPlatformProcurementTask buildTask(ThirdPlatformOrderLine line, String currency) {
        return new ThirdPlatformProcurementTask()
                .setShopName(line.getShopName())
                .setShopType(line.getShopType())
                .setOuterOrderId(line.getOuterOrderId())
                .setLineId(line.getLineId())
                .setTangbuyProductId(line.getTangbuyProductId())
                .setTangbuySkuId(line.getTangbuySkuId())
                .setQuantity(line.getQuantity())
                .setCurrency(currency)
                .setUnitPrice(line.getPrice())
                .setTaskStatus(ProcurementTaskStatus.PENDING)
                .setDelFlag(0);
    }

    private String resolveCurrency(String shopName, String outerOrderId) {
        return thirdPlatformOrderRepository.findByOuterOrderId(shopName, outerOrderId)
                .map(ThirdPlatformOrder::getCurrency)
                .orElse(null);
    }
}
