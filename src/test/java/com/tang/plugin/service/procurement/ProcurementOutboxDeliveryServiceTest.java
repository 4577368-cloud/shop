package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementPullResult;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the outbox delivery service (real repository + H2, test profile).
 * Locks the ack-based at-least-once semantics: pull is read-only + observational marker,
 * ack is the only PENDING_DELIVERY -> DELIVERED transition, CANCELLED is rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcurementOutboxDeliveryServiceTest {

    private static final String SHOP = "svc-test-shop";

    @Resource
    private ProcurementOutboxDeliveryService deliveryService;
    @Resource
    private ProcurementTaskService procurementTaskService;
    @Resource
    private ThirdPlatformProcurementTaskRepository repository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void pullReturnsOnlyDeliverableTasks() {
        Long pending = seedPending("L-pending");
        Long delivered = seedPending("L-delivered");
        deliveryService.ackByTaskId(delivered);
        seedPending("L-cancelled");
        procurementTaskService.cancelByLine(SHOP, "L-cancelled");
        softDelete(seedPending("L-deleted"));

        ProcurementPullResult result = deliveryService.pull(SHOP, 50);

        assertEquals(1, result.getPulled());
        assertEquals(List.of(pending), result.getTasks().stream()
                .map(ThirdPlatformProcurementTask::getId).toList());
    }

    @Test
    void pullAppliesObservationalMarkerWithoutChangingDeliveryStatus() {
        Long id = seedPending("L-pull-marker");

        deliveryService.pull(SHOP, 10);

        ThirdPlatformProcurementTask task = repository.findById(id).orElseThrow();
        assertEquals(1, task.getDeliveryAttempts());
        assertNotNull(task.getLastPulledAt());
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY, task.getDeliveryStatus());
    }

    @Test
    void repeatedPullBeforeAckReturnsSameTaskAndAccumulatesAttempts() {
        Long id = seedPending("L-repeat");

        ProcurementPullResult first = deliveryService.pull(SHOP, 10);
        ProcurementPullResult second = deliveryService.pull(SHOP, 10);

        assertEquals(List.of(id), first.getTasks().stream().map(ThirdPlatformProcurementTask::getId).toList());
        assertEquals(List.of(id), second.getTasks().stream().map(ThirdPlatformProcurementTask::getId).toList());
        assertEquals(2, repository.findById(id).orElseThrow().getDeliveryAttempts());
    }

    @Test
    void pullIsOrderedByIdAscAndRespectsLimit() {
        Long first = seedPending("L-a");
        Long second = seedPending("L-b");
        seedPending("L-c");

        ProcurementPullResult result = deliveryService.pull(SHOP, 2);

        assertEquals(2, result.getPulled());
        List<Long> ids = result.getTasks().stream().map(ThirdPlatformProcurementTask::getId).toList();
        assertEquals(List.of(first, second), ids);
    }

    @Test
    void ackByLineFirstReturnsDeliveredAndTransitionsTask() {
        Long id = seedPending("L-ack");

        ProcurementAckResult result = deliveryService.ackByLine(SHOP, "L-ack");

        assertEquals(ProcurementAckResult.Outcome.DELIVERED, result.getOutcome());
        assertEquals(id, result.getTaskId());
        ThirdPlatformProcurementTask task = repository.findById(id).orElseThrow();
        assertEquals(ProcurementTaskDeliveryStatus.DELIVERED, task.getDeliveryStatus());
        assertNotNull(task.getDeliveredAt());
    }

    @Test
    void ackByLineSecondReturnsAlreadyDeliveredAndKeepsDeliveredAt() {
        Long id = seedPending("L-ack-twice");

        deliveryService.ackByLine(SHOP, "L-ack-twice");
        Instant deliveredAt = repository.findById(id).orElseThrow().getDeliveredAt();

        ProcurementAckResult second = deliveryService.ackByLine(SHOP, "L-ack-twice");

        assertEquals(ProcurementAckResult.Outcome.ALREADY_DELIVERED, second.getOutcome());
        assertEquals(deliveredAt, repository.findById(id).orElseThrow().getDeliveredAt());
    }

    @Test
    void ackByTaskIdMatchesAckByLineSemantics() {
        Long id = seedPending("L-ack-by-id");

        ProcurementAckResult first = deliveryService.ackByTaskId(id);
        ProcurementAckResult second = deliveryService.ackByTaskId(id);

        assertEquals(ProcurementAckResult.Outcome.DELIVERED, first.getOutcome());
        assertEquals(ProcurementAckResult.Outcome.ALREADY_DELIVERED, second.getOutcome());
        assertEquals(ProcurementTaskDeliveryStatus.DELIVERED,
                repository.findById(id).orElseThrow().getDeliveryStatus());
    }

    @Test
    void deliveredTaskIsNoLongerPullable() {
        Long id = seedPending("L-gone-after-ack");
        deliveryService.ackByLine(SHOP, "L-gone-after-ack");

        ProcurementPullResult result = deliveryService.pull(SHOP, 50);

        assertTrue(result.getTasks().stream()
                .noneMatch(t -> t.getId().equals(id)), "delivered task must not be pulled again");
    }

    @Test
    void cancelledTaskIsNotPullableAndAckIsRejected() {
        Long id = seedPending("L-cancel");
        procurementTaskService.cancelByLine(SHOP, "L-cancel");

        ProcurementPullResult result = deliveryService.pull(SHOP, 50);
        assertTrue(result.getTasks().stream().noneMatch(t -> t.getId().equals(id)));

        CustomException byLine = assertThrows(CustomException.class,
                () -> deliveryService.ackByLine(SHOP, "L-cancel"));
        assertTrue(byLine.getMessage().contains("CANCELLED"));

        CustomException byTask = assertThrows(CustomException.class,
                () -> deliveryService.ackByTaskId(id));
        assertTrue(byTask.getMessage().contains("CANCELLED"));
    }

    @Test
    void ackMissingTaskIsRejected() {
        assertThrows(CustomException.class, () -> deliveryService.ackByLine(SHOP, "L-missing"));
        assertThrows(CustomException.class, () -> deliveryService.ackByTaskId(999_999L));
    }

    @Test
    void markPulledOnlyAffectsPulledTasks() {
        Long first = seedPending("L-pulled-1");
        Long second = seedPending("L-pulled-2");
        Long beyondLimit = seedPending("L-not-pulled");

        deliveryService.pull(SHOP, 2);

        assertEquals(1, repository.findById(first).orElseThrow().getDeliveryAttempts());
        assertEquals(1, repository.findById(second).orElseThrow().getDeliveryAttempts());
        assertEquals(0, repository.findById(beyondLimit).orElseThrow().getDeliveryAttempts(),
                "task beyond the pull limit is not marked");
    }

    private Long seedPending(String lineId) {
        return repository.saveIfAbsent(new ThirdPlatformProcurementTask()
                .setShopName(SHOP)
                .setShopType("SHOPIFY")
                .setOuterOrderId("O-" + lineId)
                .setLineId(lineId)
                .setTangbuySkuId("SKU-" + lineId)
                .setQuantity(1)
                .setTaskStatus(ProcurementTaskStatus.PENDING)
                .setDelFlag(0));
    }

    private void softDelete(Long id) {
        jdbcTemplate.update("UPDATE third_platform_procurement_task SET del_flag = 1 WHERE id = ?", id);
    }
}
