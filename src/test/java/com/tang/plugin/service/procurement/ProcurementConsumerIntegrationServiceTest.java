package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.procurement.ProcurementAckResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumeResult.ConsumptionOutcome;
import com.tang.plugin.domain.dto.procurement.ProcurementConsumptionView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementConsumption;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementConsumptionStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskDeliveryStatus;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementConsumptionRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the consumer integration service (real repositories + H2, test profile).
 * Locks P1 semantics: receive = ledger only; accept = ledger + outbox ack; orthogonal statuses;
 * (task_id, consumer_id) idempotency; write-once timestamps; CANCELLED rejected.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcurementConsumerIntegrationServiceTest {

    private static final String SHOP = "consumer-test-shop";
    private static final String CONSUMER = "main-platform";

    @Resource
    private ProcurementConsumerIntegrationService consumerService;
    @Resource
    private ProcurementTaskService procurementTaskService;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private ThirdPlatformProcurementConsumptionRepository consumptionRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void receiveFirstRecordsReceiptWithoutChangingDeliveryStatus() {
        Long taskId = seedPending("L-recv");

        ProcurementConsumeResult result = consumerService.receive(SHOP, taskId, CONSUMER, "ref-1");

        assertEquals(ConsumptionOutcome.RECEIVED, result.getConsumptionOutcome());
        assertNull(result.getDeliveryOutcome(), "receive never drives delivery ack");
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY,
                taskRepository.findById(taskId).orElseThrow().getDeliveryStatus());
        ThirdPlatformProcurementConsumption receipt =
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow();
        assertEquals(ProcurementConsumptionStatus.RECEIVED, receipt.getConsumptionStatus());
        assertNotNull(receipt.getReceivedAt());
        assertNull(receipt.getAcceptedAt());
    }

    @Test
    void receiveTwiceIsIdempotentAndKeepsReceivedAt() {
        Long taskId = seedPending("L-recv-twice");

        consumerService.receive(SHOP, taskId, CONSUMER, "ref-1");
        Instant firstReceivedAt = consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow().getReceivedAt();

        ProcurementConsumeResult second = consumerService.receive(SHOP, taskId, CONSUMER, "ref-2");

        assertEquals(ConsumptionOutcome.ALREADY_RECEIVED, second.getConsumptionOutcome());
        assertEquals(firstReceivedAt,
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow().getReceivedAt());
    }

    @Test
    void acceptFirstRecordsAcceptanceAndDriversOutboxDelivered() {
        Long taskId = seedPending("L-accept");

        ProcurementConsumeResult result = consumerService.accept(SHOP, taskId, CONSUMER, "ref-1");

        assertEquals(ConsumptionOutcome.ACCEPTED, result.getConsumptionOutcome());
        assertEquals(ProcurementAckResult.Outcome.DELIVERED, result.getDeliveryOutcome());
        ThirdPlatformProcurementTask task = taskRepository.findById(taskId).orElseThrow();
        assertEquals(ProcurementTaskDeliveryStatus.DELIVERED, task.getDeliveryStatus());
        assertNotNull(task.getDeliveredAt());
        ThirdPlatformProcurementConsumption receipt =
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow();
        assertEquals(ProcurementConsumptionStatus.ACCEPTED, receipt.getConsumptionStatus());
        assertNotNull(receipt.getAcceptedAt());
    }

    @Test
    void acceptTwiceReturnsAlreadyAcceptedAndAlreadyDeliveredWithStableTimestamps() {
        Long taskId = seedPending("L-accept-twice");

        consumerService.accept(SHOP, taskId, CONSUMER, "ref-1");
        ThirdPlatformProcurementConsumption afterFirst =
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow();
        Instant acceptedAt = afterFirst.getAcceptedAt();
        Instant deliveredAt = taskRepository.findById(taskId).orElseThrow().getDeliveredAt();

        ProcurementConsumeResult second = consumerService.accept(SHOP, taskId, CONSUMER, "ref-1");

        assertEquals(ConsumptionOutcome.ALREADY_ACCEPTED, second.getConsumptionOutcome());
        assertEquals(ProcurementAckResult.Outcome.ALREADY_DELIVERED, second.getDeliveryOutcome());
        assertEquals(acceptedAt,
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow().getAcceptedAt());
        assertEquals(deliveredAt, taskRepository.findById(taskId).orElseThrow().getDeliveredAt());
    }

    @Test
    void acceptWithoutPriorReceiveAutoCreatesAcceptedReceipt() {
        Long taskId = seedPending("L-accept-direct");

        ProcurementConsumeResult result = consumerService.accept(SHOP, taskId, CONSUMER, null);

        assertEquals(ConsumptionOutcome.ACCEPTED, result.getConsumptionOutcome());
        assertEquals(ProcurementConsumptionStatus.ACCEPTED,
                consumptionRepository.findByKey(taskId, CONSUMER).orElseThrow().getConsumptionStatus());
    }

    @Test
    void receiveAfterAcceptReportsAlreadyAccepted() {
        Long taskId = seedPending("L-recv-after-accept");
        consumerService.accept(SHOP, taskId, CONSUMER, "ref-1");

        ProcurementConsumeResult result = consumerService.receive(SHOP, taskId, CONSUMER, "ref-1");

        assertEquals(ConsumptionOutcome.ALREADY_ACCEPTED, result.getConsumptionOutcome());
    }

    @Test
    void cancelledTaskRejectsReceiveAndAccept() {
        Long taskId = seedPending("L-cancel");
        procurementTaskService.cancelByLine(SHOP, "L-cancel");

        CustomException onReceive = assertThrows(CustomException.class,
                () -> consumerService.receive(SHOP, taskId, CONSUMER, null));
        assertTrue(onReceive.getMessage().contains("CANCELLED"));

        CustomException onAccept = assertThrows(CustomException.class,
                () -> consumerService.accept(SHOP, taskId, CONSUMER, null));
        assertTrue(onAccept.getMessage().contains("CANCELLED"));
    }

    @Test
    void softDeletedTaskIsRejected() {
        Long taskId = seedPending("L-deleted");
        jdbcTemplate.update("UPDATE third_platform_procurement_task SET del_flag = 1 WHERE id = ?", taskId);

        assertThrows(CustomException.class, () -> consumerService.receive(SHOP, taskId, CONSUMER, null));
        assertThrows(CustomException.class, () -> consumerService.accept(SHOP, taskId, CONSUMER, null));
    }

    @Test
    void shopMismatchIsRejected() {
        Long taskId = seedPending("L-shop-mismatch");

        assertThrows(CustomException.class,
                () -> consumerService.receive("other-shop", taskId, CONSUMER, null));
    }

    @Test
    void missingTaskIsRejected() {
        assertThrows(CustomException.class, () -> consumerService.receive(SHOP, 999_999L, CONSUMER, null));
        assertThrows(CustomException.class, () -> consumerService.accept(SHOP, 999_999L, CONSUMER, null));
    }

    @Test
    void distinctConsumersEachGetReceiptAndDeliveryAckIsIdempotent() {
        Long taskId = seedPending("L-two-consumers");

        ProcurementConsumeResult first = consumerService.accept(SHOP, taskId, "consumer-a", null);
        ProcurementConsumeResult second = consumerService.accept(SHOP, taskId, "consumer-b", null);

        assertEquals(ConsumptionOutcome.ACCEPTED, first.getConsumptionOutcome());
        assertEquals(ProcurementAckResult.Outcome.DELIVERED, first.getDeliveryOutcome());
        assertEquals(ConsumptionOutcome.ACCEPTED, second.getConsumptionOutcome());
        assertEquals(ProcurementAckResult.Outcome.ALREADY_DELIVERED, second.getDeliveryOutcome(),
                "second consumer accepts too, but the outbox is already DELIVERED");
        assertEquals(2, consumerService.listByTask(SHOP, taskId).size());
    }

    @Test
    void viewCarriesTaskAndDeliveryStatusSnapshot() {
        Long taskId = seedPending("L-view");
        consumerService.accept(SHOP, taskId, CONSUMER, null);

        List<ProcurementConsumptionView> byTask = consumerService.listByTask(SHOP, taskId);
        assertEquals(1, byTask.size());
        ProcurementConsumptionView view = byTask.get(0);
        assertEquals(ProcurementConsumptionStatus.ACCEPTED, view.getConsumptionStatus());
        assertEquals(ProcurementTaskStatus.PENDING, view.getTaskStatus());
        assertEquals(ProcurementTaskDeliveryStatus.DELIVERED, view.getDeliveryStatus());

        List<ProcurementConsumptionView> byStatus =
                consumerService.listByStatus(SHOP, ProcurementConsumptionStatus.ACCEPTED);
        assertTrue(byStatus.stream().anyMatch(v -> v.getTaskId().equals(taskId)));
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
