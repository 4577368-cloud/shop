package com.tang.plugin.service.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.enums.order.OrderStatusTab;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mq.ProducerUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Placeholder warehouse sync: when remote order is enabled, operators can push goodsStatus
 * via MQ / future Admin poll. This job currently refreshes overdue AWAITING_PAYMENT → keep,
 * and emits heartbeat logs. Real listOrderDetail pull lands when tang-api-order is available.
 */
@Slf4j
@Service
public class ProcurementStatusSyncService {

    @Value("${tang.plugin.remote.order.enabled:false}")
    private boolean remoteEnabled;

    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private ProducerUtils producerUtils;

    /** Apply Admin goodsStatus onto a draft (called from future poller or inbound MQ). */
    public void applyGoodsStatus(Long draftOrderId, Integer goodsStatus) {
        if (draftOrderId == null || goodsStatus == null) return;
        OrderStatusTab tab = OrderStatusMapper.fromGoodsStatus(goodsStatus);
        Integer draftCode = tab == null ? null : switch (tab) {
            case PENDING_SUPPLEMENT, PENDING_PAYMENT -> DraftOrderItemEnum.AWAITING_PAYMENT.getCode();
            case PREPARING -> DraftOrderItemEnum.PROCESSING.getCode();
            case PENDING_SHIPMENT -> DraftOrderItemEnum.AWAITING_SHIPMENT.getCode();
            case IN_TRANSIT -> DraftOrderItemEnum.AWAITING_FULFILLMENT.getCode();
            case DELIVERED -> DraftOrderItemEnum.FULFILLED.getCode();
            case CANCELED -> DraftOrderItemEnum.CANCELED.getCode();
            default -> null;
        };
        LambdaUpdateWrapper<TDraftOrderDO> uw = new LambdaUpdateWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getId, draftOrderId)
                .set(TDraftOrderDO::getContent, String.valueOf(goodsStatus))
                .set(TDraftOrderDO::getUpdateTime, Instant.now());
        if (draftCode != null) {
            uw.set(TDraftOrderDO::getStatus, draftCode);
        }
        draftOrderMapper.update(null, uw);
        if (draftCode != null) {
            // Do not put goodsStatus in MQ payload — avoids re-entrant applyGoodsStatus loops.
            producerUtils.sendOrderStateChange(draftOrderId, draftCode, Map.of("source", "goodsStatus"));
        }
        log.info("Applied goodsStatus={} -> draftStatus={} draftId={}", goodsStatus, draftCode, draftOrderId);
    }

    @Scheduled(fixedDelayString = "${tang.plugin.procurement.sync-delay-ms:600000}")
    public void heartbeat() {
        if (!remoteEnabled) return;
        Long awaitingPay = draftOrderMapper.selectCount(new LambdaQueryWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getStatus, DraftOrderItemEnum.AWAITING_PAYMENT.getCode())
                .eq(TDraftOrderDO::getDelFlag, 0));
        log.debug("Procurement sync heartbeat awaitingPayment={}", awaitingPay);
    }
}
