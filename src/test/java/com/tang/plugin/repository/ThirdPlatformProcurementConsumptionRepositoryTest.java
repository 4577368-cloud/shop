package com.tang.plugin.repository;

import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementConsumption;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the consumer-receipt persistence layer (real H2, test profile).
 * Locks idempotent key, write-once accepted_at, and the reconciliation view join.
 */
@SpringBootTest
@ActiveProfiles("test")
class ThirdPlatformProcurementConsumptionRepositoryTest {

    private static final String SHOP = "consumption-repo-shop";
    private static final String CONSUMER = "main-platform";

    @Resource
    private ThirdPlatformProcurementConsumptionRepository repository;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void insertReceivedThenFindByKey() {
        Long taskId = seedTask("L-1");

        Long id = repository.insertReceived(receipt(taskId, "L-1", CONSUMER));

        ThirdPlatformProcurementConsumption found = repository.findByKey(taskId, CONSUMER).orElseThrow();
        assertEquals(id, found.getId());
        assertEquals(ProcurementConsumptionStatus.RECEIVED, found.getConsumptionStatus());
        assertNotNull(found.getReceivedAt());
    }

    @Test
    void markAcceptedTransitionsOnceAndKeepsAcceptedAt() {
        Long taskId = seedTask("L-2");
        Long id = repository.insertReceived(receipt(taskId, "L-2", CONSUMER));

        int first = repository.markAccepted(id);
        assertEquals(1, first);
        Instant acceptedAt = repository.findById(id).orElseThrow().getAcceptedAt();
        assertNotNull(acceptedAt);

        int second = repository.markAccepted(id);
        assertEquals(0, second, "re-accepting an already ACCEPTED receipt updates nothing");
        assertEquals(acceptedAt, repository.findById(id).orElseThrow().getAcceptedAt());
    }

    @Test
    void viewByTaskCarriesTaskAndDeliverySnapshot() {
        Long taskId = seedTask("L-3");
        repository.insertReceived(receipt(taskId, "L-3", CONSUMER));

        List<ProcurementConsumptionView> views = repository.listViewByTask(SHOP, taskId);

        assertEquals(1, views.size());
        ProcurementConsumptionView view = views.get(0);
        assertEquals(taskId, view.getTaskId());
        assertEquals(ProcurementConsumptionStatus.RECEIVED, view.getConsumptionStatus());
        assertEquals(ProcurementTaskStatus.PENDING, view.getTaskStatus());
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY, view.getDeliveryStatus());
    }

    @Test
    void viewByStatusFiltersByShopAndStatus() {
        Long taskId = seedTask("L-4");
        Long id = repository.insertReceived(receipt(taskId, "L-4", CONSUMER));
        repository.markAccepted(id);

        List<ProcurementConsumptionView> accepted =
                repository.listViewByStatus(SHOP, ProcurementConsumptionStatus.ACCEPTED);
        List<ProcurementConsumptionView> received =
                repository.listViewByStatus(SHOP, ProcurementConsumptionStatus.RECEIVED);

        assertTrue(accepted.stream().anyMatch(v -> v.getTaskId().equals(taskId)));
        assertTrue(received.stream().noneMatch(v -> v.getTaskId().equals(taskId)));
    }

    private Long seedTask(String lineId) {
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

    private ThirdPlatformProcurementConsumption receipt(Long taskId, String lineId, String consumerId) {
        return new ThirdPlatformProcurementConsumption()
                .setShopName(SHOP)
                .setTaskId(taskId)
                .setLineId(lineId)
                .setConsumerId(consumerId)
                .setConsumerRef("ref");
    }
}
