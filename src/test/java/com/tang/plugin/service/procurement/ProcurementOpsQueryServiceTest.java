package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.procurement.ProcurementAnomalyEntry;
import com.tang.plugin.domain.dto.procurement.ProcurementChainSummary;
import com.tang.plugin.domain.dto.procurement.ProcurementChainView;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementConsumption;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementChainAnomaly;
import com.tang.plugin.enums.procurement.ProcurementExecutionStatus;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the read-only ops/audit layer (real repositories + H2, test profile).
 * Locks chain assembly, runtime anomaly classification, severity filtering, and summary counts.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProcurementOpsQueryServiceTest {

    private static final String SHOP = "ops-test-shop";
    private static final String CONSUMER = "main-platform";

    @Resource
    private ProcurementOpsQueryService opsQueryService;
    @Resource
    private ProcurementConsumerIntegrationService consumerService;
    @Resource
    private ProcurementOutboxDeliveryService outboxDeliveryService;
    @Resource
    private ProcurementTaskService procurementTaskService;
    @Resource
    private ProcurementExecutionStubService executionStubService;
    @Resource
    private ThirdPlatformProcurementTaskRepository taskRepository;
    @Resource
    private ThirdPlatformProcurementConsumptionRepository consumptionRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM third_platform_procurement_execution WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_consumption WHERE shop_name = ?", SHOP);
        jdbcTemplate.update("DELETE FROM third_platform_procurement_task WHERE shop_name = ?", SHOP);
    }

    @Test
    void chainByTaskAssemblesTaskDeliveryAndReceipts() {
        Long taskId = seedPending("L-chain");
        consumerService.receive(SHOP, taskId, CONSUMER, "ref-1");

        ProcurementChainView view = opsQueryService.chainByTask(SHOP, taskId);

        assertEquals(taskId, view.getTaskId());
        assertEquals(ProcurementTaskStatus.PENDING, view.getTaskStatus());
        assertEquals(ProcurementTaskDeliveryStatus.PENDING_DELIVERY, view.getDeliveryStatus());
        assertEquals(1, view.getReceipts().size());
        assertFalse(view.isHasAcceptedReceipt());
        assertTrue(view.getAnomalies().contains(ProcurementChainAnomaly.RECEIVED_NOT_ACCEPTED));
    }

    @Test
    void chainByTaskRejectsMissingOrForeignShop() {
        Long taskId = seedPending("L-foreign");
        assertThrows(CustomException.class, () -> opsQueryService.chainByTask("other-shop", taskId));
        assertThrows(CustomException.class, () -> opsQueryService.chainByTask(SHOP, 999_999L));
    }

    @Test
    void chainByLineResolvesTask() {
        Long taskId = seedPending("L-byline");

        ProcurementChainView view = opsQueryService.chainByLine(SHOP, "L-byline");

        assertEquals(taskId, view.getTaskId());
        assertEquals("L-byline", view.getLineId());
    }

    @Test
    void chainByShopIsOrderedByIdAscAndRespectsLimit() {
        Long first = seedPending("L-1");
        Long second = seedPending("L-2");
        seedPending("L-3");

        List<ProcurementChainView> views = opsQueryService.chainByShop(SHOP, null, null, 2);

        assertEquals(List.of(first, second),
                views.stream().map(ProcurementChainView::getTaskId).toList());
    }

    @Test
    void chainByShopFiltersByDeliveryStatus() {
        Long pending = seedPending("L-pending");
        Long delivered = seedPending("L-delivered");
        consumerService.accept(SHOP, delivered, CONSUMER, null);

        List<ProcurementChainView> deliveredViews =
                opsQueryService.chainByShop(SHOP, null, ProcurementTaskDeliveryStatus.DELIVERED, 50);

        assertEquals(List.of(delivered),
                deliveredViews.stream().map(ProcurementChainView::getTaskId).toList());
        assertFalse(deliveredViews.stream().anyMatch(v -> v.getTaskId().equals(pending)));
    }

    @Test
    void anomaliesReturnWarnAndErrorByDefaultAndHideInfo() {
        // WARN: received but not accepted
        Long warnTask = seedPending("L-warn");
        consumerService.receive(SHOP, warnTask, CONSUMER, null);
        // INFO: delivered via direct ack, no accept receipt
        Long infoTask = seedPending("L-info");
        outboxDeliveryService.ackByTaskId(infoTask);

        List<ProcurementAnomalyEntry> defaults = opsQueryService.anomalies(SHOP, null, null, false, 50);
        assertTrue(defaults.stream().anyMatch(e -> e.getAnomaly() == ProcurementChainAnomaly.RECEIVED_NOT_ACCEPTED));
        assertFalse(defaults.stream()
                .anyMatch(e -> e.getAnomaly() == ProcurementChainAnomaly.DELIVERED_WITHOUT_ACCEPT));

        List<ProcurementAnomalyEntry> withInfo = opsQueryService.anomalies(SHOP, null, null, true, 50);
        assertTrue(withInfo.stream()
                .anyMatch(e -> e.getAnomaly() == ProcurementChainAnomaly.DELIVERED_WITHOUT_ACCEPT));
    }

    @Test
    void anomaliesSurfaceAcceptedNotDeliveredAsError() {
        Long taskId = seedPending("L-drift");
        // Craft ledger/outbox drift directly: ACCEPTED receipt but task never delivered.
        Long receiptId = consumptionRepository.insertReceived(new ThirdPlatformProcurementConsumption()
                .setShopName(SHOP)
                .setTaskId(taskId)
                .setLineId("L-drift")
                .setConsumerId(CONSUMER));
        consumptionRepository.markAccepted(receiptId);

        List<ProcurementAnomalyEntry> entries =
                opsQueryService.anomalies(SHOP, ProcurementChainAnomaly.ACCEPTED_NOT_DELIVERED, null, false, 50);

        assertEquals(1, entries.size());
        assertEquals(ProcurementChainAnomaly.Severity.ERROR, entries.get(0).getSeverity());
        assertEquals(taskId, entries.get(0).getTaskId());
    }

    @Test
    void anomaliesDetectMultiConsumerAcceptedAsInfo() {
        Long taskId = seedPending("L-multi");
        consumerService.accept(SHOP, taskId, "consumer-a", null);
        consumerService.accept(SHOP, taskId, "consumer-b", null);

        List<ProcurementAnomalyEntry> withInfo =
                opsQueryService.anomalies(SHOP, ProcurementChainAnomaly.MULTI_CONSUMER_ACCEPTED, null, true, 50);

        assertEquals(1, withInfo.size());
        assertEquals(ProcurementChainAnomaly.Severity.INFO, withInfo.get(0).getSeverity());
    }

    @Test
    void anomaliesDetectStalePendingUnpulled() {
        Long taskId = seedPending("L-stale");
        backdate(taskId, "created_at", Instant.now().minus(2, ChronoUnit.HOURS));

        List<ProcurementAnomalyEntry> entries =
                opsQueryService.anomalies(SHOP, ProcurementChainAnomaly.STALE_PENDING_UNPULLED, 60L, false, 50);

        assertEquals(1, entries.size());
        assertEquals(taskId, entries.get(0).getTaskId());
    }

    @Test
    void anomaliesDetectPulledNotDelivered() {
        Long taskId = seedPending("L-pulled");
        jdbcTemplate.update(
                "UPDATE third_platform_procurement_task SET delivery_attempts = 1, last_pulled_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), taskId);

        List<ProcurementAnomalyEntry> entries =
                opsQueryService.anomalies(SHOP, ProcurementChainAnomaly.PULLED_NOT_DELIVERED, 60L, false, 50);

        assertEquals(1, entries.size());
        assertEquals(taskId, entries.get(0).getTaskId());
    }

    @Test
    void anomaliesRespectLimit() {
        for (int i = 0; i < 5; i++) {
            Long id = seedPending("L-lim-" + i);
            consumerService.receive(SHOP, id, CONSUMER, null);
        }

        List<ProcurementAnomalyEntry> entries = opsQueryService.anomalies(SHOP, null, null, false, 3);

        assertEquals(3, entries.size());
    }

    @Test
    void summaryReportsStatusAndAnomalyCounts() {
        Long delivered = seedPending("L-s-delivered");
        consumerService.accept(SHOP, delivered, CONSUMER, null);
        Long received = seedPending("L-s-received");
        consumerService.receive(SHOP, received, CONSUMER, null);
        Long cancelled = seedPending("L-s-cancelled");
        procurementTaskService.cancelByLine(SHOP, "L-s-cancelled");

        ProcurementChainSummary summary = opsQueryService.summary(SHOP);

        assertEquals(2L, summary.getTaskStatusCounts().get("PENDING"));
        assertEquals(1L, summary.getTaskStatusCounts().get("CANCELLED"));
        assertEquals(1L, summary.getDeliveryStatusCounts().get("DELIVERED"));
        assertEquals(2L, summary.getDeliveryStatusCounts().get("PENDING_DELIVERY"));
        assertEquals(1L, summary.getConsumptionStatusCounts().get("ACCEPTED"));
        assertEquals(1L, summary.getConsumptionStatusCounts().get("RECEIVED"));
        assertEquals(1L, summary.getAnomalyCounts().get(ProcurementChainAnomaly.RECEIVED_NOT_ACCEPTED.name()));
        assertEquals(0L, summary.getAnomalyCounts().get(ProcurementChainAnomaly.ACCEPTED_NOT_DELIVERED.name()));
    }

    @Test
    void chainViewIsNullExecutionWhenNoStub() {
        Long taskId = seedPending("L-no-exec");

        ProcurementChainView view = opsQueryService.chainByTask(SHOP, taskId);

        assertFalse(view.isHasExecution());
        assertEquals(null, view.getExecution());
        assertEquals(null, view.getExecutionStatus());
    }

    @Test
    void chainViewAssemblesExecutionSnapshotAsAdditiveLayer() {
        Long taskId = seedPending("L-exec");
        consumerService.accept(SHOP, taskId, CONSUMER, null);
        executionStubService.createFromAcceptedTask(SHOP, taskId, CONSUMER, "stub-note");

        ProcurementChainView view = opsQueryService.chainByTask(SHOP, taskId);

        assertTrue(view.isHasExecution());
        assertEquals(ProcurementExecutionStatus.PENDING_EXECUTION, view.getExecutionStatus());
        assertEquals(taskId, view.getExecution().getTaskId());
        assertEquals(CONSUMER, view.getExecution().getConsumerId());
        assertEquals("stub-note", view.getExecution().getNote());
        // Existing receipts/anomaly semantics are untouched by the added execution layer.
        assertTrue(view.isHasAcceptedReceipt());
    }

    @Test
    void anomaliesDetectExecutionPendingStaleAsWarn() {
        Long taskId = seedPending("L-exec-stale");
        consumerService.accept(SHOP, taskId, CONSUMER, null);
        executionStubService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);
        jdbcTemplate.update(
                "UPDATE third_platform_procurement_execution SET created_at = ? WHERE task_id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), taskId);

        List<ProcurementAnomalyEntry> entries =
                opsQueryService.anomalies(SHOP, ProcurementChainAnomaly.EXECUTION_PENDING_STALE, 60L, false, 50);

        assertEquals(1, entries.size());
        assertEquals(taskId, entries.get(0).getTaskId());
        assertEquals(ProcurementChainAnomaly.Severity.WARN, entries.get(0).getSeverity());
    }

    @Test
    void anomaliesDetectExecutionCompletedOnCancelledAsInfo() {
        Long taskId = seedPending("L-exec-cancel");
        consumerService.accept(SHOP, taskId, CONSUMER, null);
        executionStubService.createFromAcceptedTask(SHOP, taskId, CONSUMER, null);
        executionStubService.completeStub(SHOP, taskId, null);
        procurementTaskService.cancelByLine(SHOP, "L-exec-cancel");

        List<ProcurementAnomalyEntry> defaults = opsQueryService.anomalies(
                SHOP, ProcurementChainAnomaly.EXECUTION_COMPLETED_ON_CANCELLED, null, false, 50);
        assertTrue(defaults.isEmpty());

        List<ProcurementAnomalyEntry> withInfo = opsQueryService.anomalies(
                SHOP, ProcurementChainAnomaly.EXECUTION_COMPLETED_ON_CANCELLED, null, true, 50);
        assertEquals(1, withInfo.size());
        assertEquals(taskId, withInfo.get(0).getTaskId());
        assertEquals(ProcurementChainAnomaly.Severity.INFO, withInfo.get(0).getSeverity());
    }

    @Test
    void summaryIncludesExecutionAnomalyCounts() {
        Long stale = seedPending("L-sum-exec-stale");
        consumerService.accept(SHOP, stale, CONSUMER, null);
        executionStubService.createFromAcceptedTask(SHOP, stale, CONSUMER, null);
        jdbcTemplate.update(
                "UPDATE third_platform_procurement_execution SET created_at = ? WHERE task_id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), stale);

        ProcurementChainSummary summary = opsQueryService.summary(SHOP);

        assertEquals(1L,
                summary.getAnomalyCounts().get(ProcurementChainAnomaly.EXECUTION_PENDING_STALE.name()));
        assertEquals(0L,
                summary.getAnomalyCounts().get(ProcurementChainAnomaly.EXECUTION_COMPLETED_ON_CANCELLED.name()));
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

    private void backdate(Long taskId, String column, Instant instant) {
        jdbcTemplate.update(
                "UPDATE third_platform_procurement_task SET " + column + " = ? WHERE id = ?",
                Timestamp.from(instant), taskId);
    }
}
