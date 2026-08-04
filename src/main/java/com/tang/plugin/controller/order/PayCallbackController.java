package com.tang.plugin.controller.order;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tang.common.core.domain.R;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.mapper.order.TDraftOrderLineMapper;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mq.ProducerUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tang pay callback (public). Lite: match tradeNo → move draft to PROCESSING.
 */
@Slf4j
@RestController
public class PayCallbackController {

    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private TDraftOrderLineMapper draftOrderLineMapper;
    @Resource private ProducerUtils producerUtils;

    @PostMapping({"/payCb", "/api/plugin/pay/payCb"})
    public R<?> payCb(@RequestBody Map<String, Object> body) {
        log.info("payCb payload={}", JSON.toJSONString(body));
        TreeMap<String, Object> tree = new TreeMap<>(body);
        tree.remove("sign");
        tree.remove("risk");
        Object orderNoObj = tree.get("orderNo");
        if (orderNoObj == null) {
            orderNoObj = tree.get("tradeNo");
        }
        String payOrderNo = orderNoObj == null ? null : String.valueOf(orderNoObj);
        if (StringUtils.isBlank(payOrderNo)) {
            return R.fail("missing orderNo");
        }

        TDraftOrderDO draft = draftOrderMapper.selectOne(new LambdaQueryWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getTradeNo, payOrderNo)
                .eq(TDraftOrderDO::getDelFlag, 0)
                .last("LIMIT 1"));
        if (draft == null) {
            draft = draftOrderMapper.selectOne(new LambdaQueryWrapper<TDraftOrderDO>()
                    .eq(TDraftOrderDO::getPayNo, payOrderNo)
                    .eq(TDraftOrderDO::getDelFlag, 0)
                    .last("LIMIT 1"));
        }
        if (draft == null) {
            log.error("payCb draft not found payOrderNo={}", payOrderNo);
            return R.fail("支付更新失败，支付订单未找到");
        }

        Instant now = Instant.now();
        int status = DraftOrderItemEnum.PROCESSING.getCode();
        draftOrderMapper.update(null, new LambdaUpdateWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getId, draft.getId())
                .set(TDraftOrderDO::getStatus, status)
                .set(TDraftOrderDO::getPayTime, now)
                .set(TDraftOrderDO::getUpdateTime, now));
        draftOrderLineMapper.update(null, new LambdaUpdateWrapper<TDraftOrderLineDO>()
                .eq(TDraftOrderLineDO::getOrderId, draft.getId())
                .eq(TDraftOrderLineDO::getDelFlag, 0)
                .set(TDraftOrderLineDO::getStatus, status)
                .set(TDraftOrderLineDO::getUpdateTime, now));
        producerUtils.sendOrderStateChange(draft.getId(), status, Map.of("tradeNo", payOrderNo, "payCb", true));
        log.info("payCb success draftId={} tradeNo={} -> PROCESSING", draft.getId(), payOrderNo);
        return R.ok();
    }
}
