package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult.ConsumptionOutcome;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementConsumption;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementConsumptionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Minimal consumer integration layer: records that the main platform / procurement system has
 * received or accepted an outbox task. No real procurement execution.
 * <p>
 * receive writes only the consumer ledger (never touches delivery_status). accept is the
 * business-facing ack entry: within one transaction it ensures the receipt, transitions it to
 * ACCEPTED, then drives {@code outboxDeliveryService.ackByTaskId} (PENDING_DELIVERY -> DELIVERED).
 * If the ack fails the whole accept rolls back, so ACCEPTED and DELIVERED never diverge.
 * Idempotent by (task_id, consumer_id); received_at / accepted_at are written once.
 */
@Slf4j
@Service
public class ProcurementConsumerIntegrationService {

    @Resource
    private ThirdPlatformProcurementConsumptionRepository consumptionRepository;
    @Resource
    private ThirdPlatformProcurementTaskRepository procurementTaskRepository;
    @Resource
    private ProcurementOutboxDeliveryService outboxDeliveryService;
    @Resource
    private TxManger txManger;

    /**
     * Record that {@code consumerId} received the task. Idempotent; never changes delivery_status.
     */
    public ProcurementConsumeResult receive(String shopName, Long taskId, String consumerId, String consumerRef) {
        requireArgs(shopName, taskId, consumerId);
        loadConsumableTask(shopName, taskId);

        return txManger.run(() -> {
            ThirdPlatformProcurementTask task = loadConsumableTask(shopName, taskId);
            EnsuredReceipt ensured = ensureReceipt(task, consumerId, consumerRef);

            ConsumptionOutcome outcome;
            if (ensured.created) {
                outcome = ConsumptionOutcome.RECEIVED;
            } else if (ensured.receipt.getConsumptionStatus() == ProcurementConsumptionStatus.ACCEPTED) {
                outcome = ConsumptionOutcome.ALREADY_ACCEPTED;
            } else {
                outcome = ConsumptionOutcome.ALREADY_RECEIVED;
            }
            log.info("Procurement consume received outcome={} taskId={} shopName={} consumerId={}",
                    outcome, taskId, shopName, consumerId);
            return baseResult(task, consumerId)
                    .setConsumptionOutcome(outcome)
                    .setConsumptionStatus(ensured.receipt.getConsumptionStatus());
        });
    }

    /**
     * Record that {@code consumerId} accepted the task and drive the outbox ack in the same tx.
     */
    public ProcurementConsumeResult accept(String shopName, Long taskId, String consumerId, String consumerRef) {
        requireArgs(shopName, taskId, consumerId);
        loadConsumableTask(shopName, taskId);

        return txManger.run(() -> {
            ThirdPlatformProcurementTask task = loadConsumableTask(shopName, taskId);
            EnsuredReceipt ensured = ensureReceipt(task, consumerId, consumerRef);

            int updated = consumptionRepository.markAccepted(ensured.receipt.getId());
            boolean acceptedNow = updated > 0;

            ProcurementAckResult ackResult = outboxDeliveryService.ackByTaskId(taskId);

            ConsumptionOutcome outcome = acceptedNow
                    ? ConsumptionOutcome.ACCEPTED
                    : ConsumptionOutcome.ALREADY_ACCEPTED;
            log.info("Procurement consume accepted outcome={} deliveryOutcome={} taskId={} shopName={} consumerId={}",
                    outcome, ackResult.getOutcome(), taskId, shopName, consumerId);
            return baseResult(task, consumerId)
                    .setConsumptionOutcome(outcome)
                    .setConsumptionStatus(ProcurementConsumptionStatus.ACCEPTED)
                    .setDeliveryOutcome(ackResult.getOutcome());
        });
    }

    public List<ProcurementConsumptionView> listByTask(String shopName, Long taskId) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            throw new CustomException("listByTask requires shopName and taskId");
        }
        return consumptionRepository.listViewByTask(shopName, taskId);
    }

    public List<ProcurementConsumptionView> listByStatus(String shopName, ProcurementConsumptionStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            throw new CustomException("listByStatus requires shopName and status");
        }
        return consumptionRepository.listViewByStatus(shopName, status);
    }

    private EnsuredReceipt ensureReceipt(ThirdPlatformProcurementTask task, String consumerId, String consumerRef) {
        return consumptionRepository.findByKey(task.getId(), consumerId)
                .map(existing -> new EnsuredReceipt(existing, false))
                .orElseGet(() -> {
                    ThirdPlatformProcurementConsumption receipt = new ThirdPlatformProcurementConsumption()
                            .setShopName(task.getShopName())
                            .setTaskId(task.getId())
                            .setLineId(task.getLineId())
                            .setConsumerId(consumerId)
                            .setConsumerRef(consumerRef);
                    Long id = consumptionRepository.insertReceived(receipt);
                    ThirdPlatformProcurementConsumption created = consumptionRepository.findById(id)
                            .orElseThrow(() -> new CustomException(
                                    "Consumption receipt not found after insert, id=" + id));
                    return new EnsuredReceipt(created, true);
                });
    }

    private ThirdPlatformProcurementTask loadConsumableTask(String shopName, Long taskId) {
        ThirdPlatformProcurementTask task = procurementTaskRepository.findById(taskId)
                .orElseThrow(() -> new CustomException("Procurement task not found, taskId=" + taskId));
        if (task.getDelFlag() != null && task.getDelFlag() != 0) {
            throw new CustomException("Procurement task is soft-deleted, cannot consume, taskId=" + taskId);
        }
        if (!StringUtils.equals(shopName, task.getShopName())) {
            throw new CustomException("Procurement task shop mismatch, taskId=" + taskId
                    + ", expected shopName=" + shopName + ", actual=" + task.getShopName());
        }
        if (task.getTaskStatus() == ProcurementTaskStatus.CANCELLED) {
            throw new CustomException("Cannot consume a CANCELLED task, taskId=" + taskId
                    + ", lineId=" + task.getLineId());
        }
        return task;
    }

    private ProcurementConsumeResult baseResult(ThirdPlatformProcurementTask task, String consumerId) {
        return new ProcurementConsumeResult()
                .setTaskId(task.getId())
                .setShopName(task.getShopName())
                .setLineId(task.getLineId())
                .setConsumerId(consumerId);
    }

    private void requireArgs(String shopName, Long taskId, String consumerId) {
        if (StringUtils.isBlank(shopName) || taskId == null || StringUtils.isBlank(consumerId)) {
            throw new CustomException("consume requires shopName, taskId and consumerId");
        }
    }

    private record EnsuredReceipt(ThirdPlatformProcurementConsumption receipt, boolean created) {
    }
}
