package com.tang.plugin.service.order.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TDraftOrderPackageDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.domain.query.order.DraftOrderPackageCreateReq;
import com.tang.plugin.domain.query.order.DraftOrderPurchaseReq;
import com.tang.plugin.domain.vo.order.DraftOrderPurchaseAmountVO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.mapper.order.TDraftOrderLineMapper;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mapper.order.TDraftOrderPackageMapper;
import com.tang.plugin.mapper.order.TOrderLinePurchaseMapper;
import com.tang.plugin.mq.ProducerUtils;
import com.tang.plugin.sdk.order.RemoteOrderSdkClient;
import com.tang.plugin.service.order.DraftOrderPackageAmountManager;
import com.tang.plugin.service.order.InnerOrderSyncManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lite uniOrderByLines / uniOrderSingle — dropship only.
 * No stock collision, discounts, tier prices, combine goods, or materials.
 */
@Slf4j
@Service
public class InnerOrderSyncManagerImpl implements InnerOrderSyncManager {

    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private TDraftOrderLineMapper draftOrderLineMapper;
    @Resource private TOrderLinePurchaseMapper orderLinePurchaseMapper;
    @Resource private TDraftOrderPackageMapper draftOrderPackageMapper;
    @Resource private RemoteOrderSdkClient remoteOrderSdkClient;
    @Resource private DraftOrderPackageAmountManager packageAmountManager;
    @Resource private TxManger txManger;
    @Resource private ProducerUtils producerUtils;

    @Override
    public UniOrderCreateResDTO uniOrderByLines(DraftOrderPurchaseReq req, Long userId,
                                                List<TDraftOrderLineDO> lines,
                                                DraftOrderPackageCreateReq packageCreateInfo) {
        if (CollectionUtils.isEmpty(lines)) {
            throw new CustomException("no lines to purchase");
        }
        Long orderId = lines.get(0).getOrderId();
        TDraftOrderDO order = draftOrderMapper.selectById(orderId);
        if (order == null || Objects.equals(order.getDelFlag(), 1)) {
            throw new CustomException("order not exist");
        }

        List<Long> lineIds = lines.stream().map(TDraftOrderLineDO::getId).collect(Collectors.toList());
        List<TOrderLinePurchaseDO> purchases = orderLinePurchaseMapper.selectList(
                new LambdaQueryWrapper<TOrderLinePurchaseDO>()
                        .in(TOrderLinePurchaseDO::getOrderLineId, lineIds)
                        .eq(TOrderLinePurchaseDO::getDelFlag, 0));
        if (CollectionUtils.isEmpty(purchases)) {
            throw new CustomException("pls match goods.");
        }

        // Group by thirdShopId (no STOCK_USE branch in lite)
        Map<String, List<TOrderLinePurchaseDO>> byShop = purchases.stream()
                .collect(Collectors.groupingBy(p -> StringUtils.defaultIfBlank(p.getThirdShopId(), "DEFAULT")));
        log.info("uniOrderSingle lite groups={} orderId={}", byShop.size(), orderId);

        UniOrderCreateResDTO res = remoteOrderSdkClient.uniOrder(order, lines, purchases, packageCreateInfo);
        if (res == null || StringUtils.isBlank(res.getTradeNo())) {
            throw new CustomException("error create order.");
        }

        DraftOrderPurchaseAmountVO amountVO = packageAmountManager.cal(order, packageCreateInfo);

        txManger.run(() -> {
            // Write back itemNo
            Map<String, String> orderNoMap = res.getOrderNoMap() == null ? Map.of() : res.getOrderNoMap();
            for (TOrderLinePurchaseDO p : purchases) {
                String itemNo = orderNoMap.get(String.valueOf(p.getId()));
                if (StringUtils.isNotBlank(itemNo)) {
                    orderLinePurchaseMapper.update(null, new LambdaUpdateWrapper<TOrderLinePurchaseDO>()
                            .eq(TOrderLinePurchaseDO::getId, p.getId())
                            .set(TOrderLinePurchaseDO::getItemNo, itemNo));
                }
            }
            // Lines → awaiting payment
            draftOrderLineMapper.update(null, new LambdaUpdateWrapper<TDraftOrderLineDO>()
                    .eq(TDraftOrderLineDO::getOrderId, orderId)
                    .eq(TDraftOrderLineDO::getDelFlag, 0)
                    .set(TDraftOrderLineDO::getStatus, DraftOrderItemEnum.AWAITING_PAYMENT.getCode()));

            Instant expire = res.getExpireTime();
            draftOrderMapper.update(null, new LambdaUpdateWrapper<TDraftOrderDO>()
                    .eq(TDraftOrderDO::getId, orderId)
                    .set(TDraftOrderDO::getStatus, DraftOrderItemEnum.AWAITING_PAYMENT.getCode())
                    .set(TDraftOrderDO::getPayNo, res.getTradeNo())
                    .set(TDraftOrderDO::getTradeNo, res.getTradeNo())
                    .set(TDraftOrderDO::getExpireTime, expire == null ? null : expire.toEpochMilli()));

            if (packageCreateInfo != null && packageCreateInfo.getLineId() != null) {
                TDraftOrderPackageDO pkg = new TDraftOrderPackageDO()
                        .setOrderId(orderId)
                        .setUserId(userId)
                        .setBzNo("TL" + System.currentTimeMillis())
                        .setLineId(packageCreateInfo.getLineId())
                        .setLineName(packageCreateInfo.getLineName())
                        .setDeliveryTime(packageCreateInfo.getDeliveryTime())
                        .setComment(packageCreateInfo.getPackageComment())
                        .setPackageChoosedContent(JSON.toJSONString(packageCreateInfo.getPackageChoosedContent()))
                        .setPackageFeeContent(JSON.toJSONString(amountVO))
                        .setTotalAmountPre(amountVO.getPackageAmountCny())
                        .setFillAmount(BigDecimal.ZERO)
                        .setFilledAmount(BigDecimal.ZERO)
                        .setRefundAmount(BigDecimal.ZERO)
                        .setChannel(order.getChannel())
                        .setCountry(order.getCountry())
                        .setDelFlag(0)
                        .setCreateTime(Instant.now())
                        .setUpdateTime(Instant.now());
                draftOrderPackageMapper.insert(pkg);
                producerUtils.sendPackageEvent(orderId, pkg.getId(), "CREATE");
            }
        });

        producerUtils.sendOrderStateChange(orderId, DraftOrderItemEnum.AWAITING_PAYMENT.getCode(),
                Map.of("tradeNo", res.getTradeNo()));
        return res;
    }

    @Override
    public void syncOrderStatus(Long orderId, Integer status) {
        if (orderId == null || status == null) return;
        draftOrderMapper.update(null, new LambdaUpdateWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getId, orderId)
                .set(TDraftOrderDO::getStatus, status));
        draftOrderLineMapper.update(null, new LambdaUpdateWrapper<TDraftOrderLineDO>()
                .eq(TDraftOrderLineDO::getOrderId, orderId)
                .eq(TDraftOrderLineDO::getDelFlag, 0)
                .set(TDraftOrderLineDO::getStatus, status));
        producerUtils.sendOrderStateChange(orderId, status, null);
    }
}
