package com.tang.plugin.mq;

import com.alibaba.fastjson2.JSON;
import com.tang.plugin.constant.PluginMqConstant;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ProducerUtils {

    @Resource
    private MqProducer mqProducer;

    public void sendOrderStateChange(Long orderId, Integer status, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orderId", orderId);
        body.put("status", status);
        if (extra != null) body.putAll(extra);
        mqProducer.send(PluginMqConstant.TOPIC.PLUGIN_ORDER_STATE_CHANGE_TOPIC, JSON.toJSONString(body));
    }

    public void sendPackageEvent(Long orderId, Long packageId, String action) {
        Map<String, Object> body = Map.of(
                "orderId", orderId,
                "packageId", packageId == null ? 0L : packageId,
                "action", action);
        mqProducer.send(PluginMqConstant.TOPIC.PLUGIN_ORDER_PACKAGE_TOPIC, JSON.toJSONString(body));
    }

    public void sendExpressUpdate(Long packageId, String expressNo) {
        Map<String, Object> body = Map.of("packageId", packageId, "expressNo", expressNo == null ? "" : expressNo);
        mqProducer.send(PluginMqConstant.TOPIC.PLUGIN_ORDER_EXPRESS_UPDATE_TOPIC, JSON.toJSONString(body));
    }

    public void sendRefundEvent(Long orderId, String refundNo) {
        Map<String, Object> body = Map.of("orderId", orderId, "refundNo", refundNo == null ? "" : refundNo);
        mqProducer.send(PluginMqConstant.TOPIC.PLUGIN_ORDER_REFUND_TOPIC, JSON.toJSONString(body));
    }

    public void sendRepairFeeEvent(Long orderId, String payTradeNo) {
        Map<String, Object> body = Map.of("orderId", orderId, "payTradeNo", payTradeNo == null ? "" : payTradeNo);
        mqProducer.send(PluginMqConstant.TOPIC.PLUGIN_ORDER_REPAIR_FEE_TOPIC, JSON.toJSONString(body));
    }
}
