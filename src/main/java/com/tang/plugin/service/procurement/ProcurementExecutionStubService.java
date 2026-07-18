package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult;
import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult.Outcome;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementConsumptionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementExecutionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Minimal execution placeholder layer built on top of an accepted task. NOT real procurement
 * execution — no supplier ordering, no logistics.
 * <p>
 * create is explicit (never auto-triggered) and requires an ACCEPTED consumption receipt.
 * complete only checks the execution stub's own state (PENDING_EXECUTION -> COMPLETED_STUB); it
 * deliberately does NOT re-check task_status. execution_status is orthogonal to task/delivery/
 * consumption status and is never written back to them. A CANCELLED task with a COMPLETED_STUB
 * execution is an explainable combination (surfaced by read-only queries), not an illegal state.
 */
@Slf4j
@Service
public class ProcurementExecutionStubService {

    @Resource
    private ThirdPlatformProcurementExecutionRepository executionRepository;
    @Resource
    private ThirdPlatformProcurementTaskRepository procurementTaskRepository;
    @Resource
    private ThirdPlatformProcurementConsumptionRepository consumptionRepository;
    @Resource
    private TxManger txManger;

    /**
     * Ensure a PENDING_EXECUTION stub for an accepted task. Idempotent by task_id.
     * Rejects missing / soft-deleted / shop-mismatched / CANCELLED tasks, and tasks without an
     * ACCEPTED receipt.
     */
    public ProcurementExecutionResult createFromAcceptedTask(String shopName, Long taskId,
                                                             String consumerId, String note) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            throw new CustomException("createFromAcceptedTask requires shopName and taskId");
        }
        ThirdPlatformProcurementTask task = loadActiveTask(shopName, taskId);
        if (task.getTaskStatus() == ProcurementTaskStatus.CANCELLED) {
            throw new CustomException("Cannot create execution for a CANCELLED task, taskId=" + taskId);
        }
        if (!hasAcceptedReceipt(shopName, taskId)) {
            throw new CustomException("Cannot create execution before the task is accepted, taskId=" + taskId);
        }

        return txManger.run(() -> {
            ThirdPlatformProcurementExecution existing = executionRepository.findByTask(taskId).orElse(null);
            if (existing != null) {
                log.info("Procurement execution create idempotent (exists) taskId={} shopName={} status={}",
                        taskId, shopName, existing.getExecutionStatus());
                return baseResult(task, consumerId)
                        .setOutcome(Outcome.ALREADY_EXISTS)
                        .setExecutionStatus(existing.getExecutionStatus());
            }
            Long id = executionRepository.insertPending(new ThirdPlatformProcurementExecution()
                    .setShopName(task.getShopName())
                    .setTaskId(task.getId())
                    .setLineId(task.getLineId())
                    .setConsumerId(consumerId)
                    .setNote(note));
            log.info("Procurement execution created id={} taskId={} shopName={} consumerId={}",
                    id, taskId, shopName, consumerId);
            return baseResult(task, consumerId)
                    .setOutcome(Outcome.CREATED)
                    .setExecutionStatus(ProcurementExecutionStatus.PENDING_EXECUTION);
        });
    }

    /**
     * Mark the stub COMPLETED_STUB (placeholder only, no real work). Orthogonal to task_status:
     * it does NOT re-check whether the task is CANCELLED. Idempotent; completed_at written once.
     */
    public ProcurementExecutionResult completeStub(String shopName, Long taskId, String note) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            throw new CustomException("completeStub requires shopName and taskId");
        }
        ThirdPlatformProcurementExecution execution = executionRepository.findByTask(taskId)
                .orElseThrow(() -> new CustomException("Execution stub not found, taskId=" + taskId));
        if (execution.getDelFlag() != null && execution.getDelFlag() != 0) {
            throw new CustomException("Execution stub is soft-deleted, taskId=" + taskId);
        }
        if (!StringUtils.equals(shopName, execution.getShopName())) {
            throw new CustomException("Execution stub shop mismatch, taskId=" + taskId
                    + ", expected shopName=" + shopName + ", actual=" + execution.getShopName());
        }

        ProcurementExecutionResult result = new ProcurementExecutionResult()
                .setTaskId(taskId)
                .setShopName(execution.getShopName())
                .setLineId(execution.getLineId())
                .setConsumerId(execution.getConsumerId());

        if (execution.getExecutionStatus() == ProcurementExecutionStatus.COMPLETED_STUB) {
            log.info("Procurement execution complete idempotent (already COMPLETED_STUB) taskId={} shopName={}",
                    taskId, shopName);
            return result.setOutcome(Outcome.ALREADY_COMPLETED)
                    .setExecutionStatus(ProcurementExecutionStatus.COMPLETED_STUB);
        }

        int updated = txManger.run(() -> executionRepository.markCompleted(execution.getId(), note));
        Outcome outcome = updated > 0 ? Outcome.COMPLETED : Outcome.ALREADY_COMPLETED;
        log.info("Procurement execution complete outcome={} taskId={} shopName={}", outcome, taskId, shopName);
        return result.setOutcome(outcome).setExecutionStatus(ProcurementExecutionStatus.COMPLETED_STUB);
    }

    public List<ThirdPlatformProcurementExecution> listByStatus(String shopName,
                                                                ProcurementExecutionStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            throw new CustomException("listByStatus requires shopName and status");
        }
        return executionRepository.listByShopStatus(shopName, status);
    }

    public ThirdPlatformProcurementExecution getByTask(String shopName, Long taskId) {
        if (StringUtils.isBlank(shopName) || taskId == null) {
            throw new CustomException("getByTask requires shopName and taskId");
        }
        ThirdPlatformProcurementExecution execution = executionRepository.findByTask(taskId)
                .orElseThrow(() -> new CustomException("Execution stub not found, taskId=" + taskId));
        if (!StringUtils.equals(shopName, execution.getShopName())) {
            throw new CustomException("Execution stub shop mismatch, taskId=" + taskId);
        }
        return execution;
    }

    private boolean hasAcceptedReceipt(String shopName, Long taskId) {
        return consumptionRepository.listViewByTask(shopName, taskId).stream()
                .anyMatch(r -> r.getConsumptionStatus() == ProcurementConsumptionStatus.ACCEPTED);
    }

    private ThirdPlatformProcurementTask loadActiveTask(String shopName, Long taskId) {
        ThirdPlatformProcurementTask task = procurementTaskRepository.findById(taskId)
                .orElseThrow(() -> new CustomException("Procurement task not found, taskId=" + taskId));
        if (task.getDelFlag() != null && task.getDelFlag() != 0) {
            throw new CustomException("Procurement task is soft-deleted, taskId=" + taskId);
        }
        if (!StringUtils.equals(shopName, task.getShopName())) {
            throw new CustomException("Procurement task shop mismatch, taskId=" + taskId
                    + ", expected shopName=" + shopName + ", actual=" + task.getShopName());
        }
        return task;
    }

    private ProcurementExecutionResult baseResult(ThirdPlatformProcurementTask task, String consumerId) {
        return new ProcurementExecutionResult()
                .setTaskId(task.getId())
                .setShopName(task.getShopName())
                .setLineId(task.getLineId())
                .setConsumerId(consumerId);
    }
}
