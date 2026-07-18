package com.tang.plugin.repository;

import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the outbox persistence layer. Uses a real H2 datasource (test profile)
 * to lock the P1 delivery query/marker semantics; no mocking of the repository.
 */
@SpringBootTest
@ActiveProfiles("test")
class ThirdPlatformProcurementTaskRepositoryTest {

    private static final String SHOP = "repo-test-shop";

    @Resource
    private ThirdPlatformProcurementTaskRepository repository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void listDeliverableReturnsOnlyPendingUndeliveredActiveTasks() {
        Long pending = seedPending("L-pending");
        Long delivered = seedPending("L-delivered");
        repository.markDelivered(delivered);
        Long cancelled = seedPending("L-cancelled");
        repository.updateStatus(cancelled, ProcurementTaskStatus.CANCELLED);
        Long softDeleted = seedPending("L-deleted");
        softDelete(softDeleted);

        List<ThirdPlatformProcurementTask> deliverable = repository.listDeliverable(SHOP, 50);

        assertEquals(1, deliverable.size(), "only the active PENDING/PENDING_DELIVERY task is deliverable");
        assertEquals(pending, deliverable.get(0).getId());
    }

    @Test
    void listDeliverableIsOrderedByIdAscAndRespectsLimit() {
        Long first = seedPending("L-1");
        Long second = seedPending("L-2");
        seedPending("L-3");

        List<ThirdPlatformProcurementTask> limited = repository.listDeliverable(SHOP, 2);

        assertEquals(2, limited.size());
        assertEquals(first, limited.get(0).getId());
        assertEquals(second, limited.get(1).getId());
        assertTrue(limited.get(0).getId() < limited.get(1).getId(), "ordered by id ASC");
    }

    @Test
    void markPulledIncrementsAttemptsSetsLastPulledAndKeepsDeliveryStatus() {
        Long pulled = seedPending("L-pulled");
        Long untouched = seedPending("L-untouched");

        int affected = repository.markPulled(List.of(pulled));

        assertEquals(1, affected);
        ThirdPlatformProcurementTask afterPulled = repository.findById(pulled).orElseThrow();
        assertEquals(1, afterPulled.getDeliveryAttempts());
        assertNotNull(afterPulled.getLastPulledAt());
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY, afterPulled.getDeliveryStatus());

        ThirdPlatformProcurementTask afterUntouched = repository.findById(untouched).orElseThrow();
        assertEquals(0, afterUntouched.getDeliveryAttempts(), "tasks not in the id list are untouched");
        assertNull(afterUntouched.getLastPulledAt());
    }

    @Test
    void markPulledAccumulatesAcrossCalls() {
        Long id = seedPending("L-accumulate");

        repository.markPulled(List.of(id));
        repository.markPulled(List.of(id));

        assertEquals(2, repository.findById(id).orElseThrow().getDeliveryAttempts());
    }

    @Test
    void markDeliveredTransitionsOnceAndIsIdempotent() {
        Long id = seedPending("L-deliver");

        int firstUpdate = repository.markDelivered(id);
        assertEquals(1, firstUpdate);
        ThirdPlatformProcurementTask delivered = repository.findById(id).orElseThrow();
        assertEquals(ProcurementTaskDeliveryStatus.DELIVERED, delivered.getDeliveryStatus());
        Instant deliveredAt = delivered.getDeliveredAt();
        assertNotNull(deliveredAt);

        int secondUpdate = repository.markDelivered(id);
        assertEquals(0, secondUpdate, "re-delivering an already DELIVERED row updates nothing");
        assertEquals(deliveredAt, repository.findById(id).orElseThrow().getDeliveredAt(),
                "delivered_at is never refreshed");
    }

    @Test
    void listByDeliveryStatusScopesByShopAndStatus() {
        Long pending = seedPending("L-p");
        Long delivered = seedPending("L-d");
        repository.markDelivered(delivered);

        List<ThirdPlatformProcurementTask> pendingList =
                repository.listByDeliveryStatus(SHOP, ProcurementTaskDeliveryStatus.PENDING_DELIVERY);
        List<ThirdPlatformProcurementTask> deliveredList =
                repository.listByDeliveryStatus(SHOP, ProcurementTaskDeliveryStatus.DELIVERED);

        assertEquals(List.of(pending), pendingList.stream().map(ThirdPlatformProcurementTask::getId).toList());
        assertEquals(List.of(delivered), deliveredList.stream().map(ThirdPlatformProcurementTask::getId).toList());
    }

    @Test
    void newTaskDefaultsToPendingDeliveryWithZeroAttempts() {
        Long id = seedPending("L-defaults");

        ThirdPlatformProcurementTask task = repository.findById(id).orElseThrow();
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY, task.getDeliveryStatus());
        assertEquals(0, task.getDeliveryAttempts());
        assertNull(task.getDeliveredAt());
        assertNull(task.getLastPulledAt());
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
