package com.tang.plugin.mq;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tang.plugin.constant.PluginMqConstant;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.service.order.ProcurementStatusSyncService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-process handler for order state changes.
 * When RocketMQ is enabled, wire a @RocketMQMessageListener that delegates here.
 * Payload may include goodsStatus for warehouse fine-grain sync.
 */
@Slf4j
@Component
public class DraftOrderSyncConsumer {

    @Resource
    private TDraftOrderMapper draftOrderMapper;
    @Resource
    private ProcurementStatusSyncService procurementStatusSyncService;

    public void onOrderStateChange(String payload) {
        JSONObject obj = JSON.parseObject(payload);
        Long orderId = obj.getLong("orderId");
        Integer status = obj.getInteger("status");
        Integer goodsStatus = obj.getInteger("goodsStatus");
        if (orderId == null) {
            log.warn("DraftOrderSyncConsumer skip invalid payload={}", payload);
            return;
        }
        if (goodsStatus != null) {
            procurementStatusSyncService.applyGoodsStatus(orderId, goodsStatus);
            return;
        }
        if (status == null) {
            log.warn("DraftOrderSyncConsumer skip missing status payload={}", payload);
            return;
        }
        draftOrderMapper.update(null, new LambdaUpdateWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getId, orderId)
                .set(TDraftOrderDO::getStatus, status));
        log.info("Draft order status updated orderId={} status={} ({})",
                orderId, status, DraftOrderItemEnum.ofCode(status));
    }

    public String topic() {
        return PluginMqConstant.TOPIC.PLUGIN_ORDER_STATE_CHANGE_TOPIC;
    }
}
