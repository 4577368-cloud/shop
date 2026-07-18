package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult;
import com.tang.plugin.domain.dto.procurement.ProcurementExecutionResult.Outcome;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementExecution;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementExecutionRepository;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the execution stub service (real repositories + H2, test profile).
 * Locks P1 semantics: create requires ACCEPTED + rejects CANCELLED; complete is orthogonal to
 * task_status (a CANCELLED task with COMPLETED_STUB is legal); idempotency + write-once completed_at.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcurementExecutionStubServiceTest {

    private static final String SHOP = "exec-test-shop";
    private static final String CONSUMER = "main-platform";

    @Resource
    private ProcurementExecutionStubService executionService;
    @Resource
    private ProcurementConsumerIntegrationService consumerService;
    @Resource
    private ProcurementTaskService procurementTaskService;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private ThirdPlatformProcurementExecutionRepository executionRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_execution WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void createFromAcceptedTaskCreatesPendingStub() {
        Long taskId = acceptedTask("L-create");

        ProcurementExecutionResult result = executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, "n1");

        assertEquals(Outcome.CREATED, result.getOutcome());
        assertEquals(ProcurementExecutionStatus.PENDING_EXECUTION, result.getExecutionStatus());
        assertEquals(ProcurementExecutionStatus.PENDING_EXECUTION,
                executionRepository.findByTask(taskId).orElseThrow().getExecutionStatus());
    }

    @Test
    void createIsIdempotentByTask() {
        Long taskId = acceptedTask("L-create-twice");

        executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, "n1");
        ProcurementExecutionResult second = executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, "n2");

        assertEquals(Outcome.ALREADY_EXISTS, second.getOutcome());
    }

    @Test
    void createRejectedWhenNotAccepted() {
        Long taskId = seedPending("L-not-accepted");
        // only received, not accepted
        consumerService.receive(SHOP, taskId, CONSUMER, null);

        CustomException ex = assertThrows(CustomException.class,
                () -> executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null));
        assertNotNull(ex.getMessage());
    }

    @Test
    void createRejectedWhenTaskCancelled() {
        Long taskId = seedPending("L-cancel-create");
        procurementTaskService.cancelByLine(SHOP, "L-cancel-create");

        assertThrows(CustomException.class,
                () -> executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null));
    }

    @Test
    void completeFirstThenIdempotentKeepsCompletedAt() {
        Long taskId = acceptedTask("L-complete");
        executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);

        ProcurementExecutionResult first = executionService.completeStub(SHOP, taskId, "done");
        assertEquals(Outcome.COMPLETED, first.getOutcome());
        Instant completedAt = executionRepository.findByTask(taskId).orElseThrow().getCompletedAt();
        assertNotNull(completedAt);

        ProcurementExecutionResult second = executionService.completeStub(SHOP, taskId, "again");
        assertEquals(Outcome.ALREADY_COMPLETED, second.getOutcome());
        assertEquals(completedAt, executionRepository.findByTask(taskId).orElseThrow().getCompletedAt());
    }

    @Test
    void completeIsOrthogonalToTaskStatusCancelled() {
        Long taskId = acceptedTask("L-cancel-complete");
        executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);
        // Cancel the task AFTER the stub exists; complete must still succeed (orthogonal).
        procurementTaskService.cancelByLine(SHOP, "L-cancel-complete");

        ProcurementExecutionResult result = executionService.completeStub(SHOP, taskId, null);

        assertEquals(Outcome.COMPLETED, result.getOutcome());
        assertEquals(ProcurementExecutionStatus.COMPLETED_STUB,
                executionRepository.findByTask(taskId).orElseThrow().getExecutionStatus());
        assertEquals(ProcurementTaskStatus.CANCELLED,
                taskRepository.findById(taskId).orElseThrow().getTaskStatus());
    }

    @Test
    void completeRejectedWhenStubMissing() {
        Long taskId = acceptedTask("L-no-stub");
        assertThrows(CustomException.class, () -> executionService.completeStub(SHOP, taskId, null));
    }

    @Test
    void listByStatusAndGetByTask() {
        Long taskId = acceptedTask("L-list");
        executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);

        assertEquals(1, executionService.listByStatus(SHOP, ProcurementExecutionStatus.PENDING_EXECUTION).size());
        assertEquals(taskId, executionService.getByTask(SHOP, taskId).getTaskId());
    }

    @Test
    void shopMismatchRejected() {
        Long taskId = acceptedTask("L-shop");
        executionService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);
        assertThrows(CustomException.class, () -> executionService.completeStub("other-shop", taskId, null));
        assertThrows(CustomException.class, () -> executionService.getByTask("other-shop", taskId));
    }

    private Long acceptedTask(String lineId) {
        Long taskId = seedPending(lineId);
        consumerService.accept(SHOP, taskId, CONSUMER, null);
        return taskId;
    }

    private Long seedPending(String lineId) {
        return taskRepository.saveIfAbsent(new ThirdPlatformProcurementTask()
                .setShopName(SHOP)
                .setShopType("SHOPIFY")
                .setOuterOrderId("O-" + lineId)
                .setLineId(lineId)
                .setTangbuySkuId("SKU-" + lineId)
                .setQuantity(1)
                .setTaskStatus(ProcurementTaskStatus.PENDING)
                .setDelFlag(0));
    }
}
