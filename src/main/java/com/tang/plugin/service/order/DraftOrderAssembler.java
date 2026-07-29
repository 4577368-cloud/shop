package com.tang.plugin.service.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ExternalOrderLine;
import com.tang.plugin.domain.entity.order.TDraftOrderAddressDO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.enums.order.PluginOrderTypeEnum;
import com.tang.plugin.mapper.order.TDraftOrderAddressMapper;
import com.tang.plugin.mapper.order.TDraftOrderLineMapper;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mapper.order.TOrderLinePurchaseMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Builds t_draft_order* rows from Shopify ExternalOrder (dropship type=1 only).
 */
@Slf4j
@Service
public class DraftOrderAssembler {

    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private TDraftOrderLineMapper draftOrderLineMapper;
    @Resource private TOrderLinePurchaseMapper orderLinePurchaseMapper;
    @Resource private TDraftOrderAddressMapper draftOrderAddressMapper;

    /**
     * Idempotent by (shopName, outerOrderId). Returns draft order id.
     */
    public Long upsertFromExternal(PluginShopBO shopBO, ExternalOrder externalOrder, Long userId) {
        if (shopBO == null || externalOrder == null || StringUtils.isBlank(externalOrder.getOrderId())) {
            return null;
        }
        String shopName = shopBO.getShopName();
        String outerOrderId = externalOrder.getOrderId();

        TDraftOrderDO existing = draftOrderMapper.selectOne(new LambdaQueryWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getShopName, shopName)
                .eq(TDraftOrderDO::getOuterOrderId, outerOrderId)
                .eq(TDraftOrderDO::getDelFlag, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("Draft order idempotent hit id={} shop={} outer={}", existing.getId(), shopName, outerOrderId);
            return existing.getId();
        }

        BigDecimal purchase = BigDecimal.ZERO;
        if (CollectionUtils.isNotEmpty(externalOrder.getLines())) {
            for (ExternalOrderLine line : externalOrder.getLines()) {
                if (line != null && line.getPrice() != null && line.getQuantity() != null) {
                    purchase = purchase.add(line.getPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
                }
            }
        }

        TDraftOrderDO order = new TDraftOrderDO()
                .setUserId(userId)
                .setStatus(DraftOrderItemEnum.AWAITING.getCode())
                .setChannel(shopBO.getShopType() == null ? "SHOPIFY" : shopBO.getShopType().name())
                .setPurchaseAmount(purchase)
                .setRefundGoodsAmount(BigDecimal.ZERO)
                .setLanguage("en")
                .setEmail(externalOrder.getEmail())
                .setCountry(externalOrder.getCountryCode())
                .setCountryId(externalOrder.getCountryId() == null ? null : String.valueOf(externalOrder.getCountryId()))
                .setDelFlag(0)
                .setOvertimeFlag(0)
                .setType(PluginOrderTypeEnum.EXTERNAL_PULL)
                .setShopName(shopName)
                .setOuterOrderId(outerOrderId)
                .setCreateTime(Instant.now())
                .setUpdateTime(Instant.now());
        draftOrderMapper.insert(order);
        Long orderId = order.getId();

        TDraftOrderAddressDO address = new TDraftOrderAddressDO()
                .setOrderId(orderId)
                .setUserId(userId)
                .setEmail(externalOrder.getEmail())
                .setName(externalOrder.getEmail())
                .setAddress1(externalOrder.getAddress1())
                .setAddress2(externalOrder.getAddress2())
                .setCity(externalOrder.getCity())
                .setZip(externalOrder.getZip())
                .setProvince(externalOrder.getProvinceCode())
                .setCountry(externalOrder.getCountryCode())
                .setCountryCode(externalOrder.getCountryCode())
                .setPhone(externalOrder.getPhone())
                .setDelFlag(0)
                .setCreateTime(Instant.now())
                .setUpdateTime(Instant.now());
        draftOrderAddressMapper.insert(address);

        if (CollectionUtils.isNotEmpty(externalOrder.getLines())) {
            for (ExternalOrderLine line : externalOrder.getLines()) {
                if (line == null || StringUtils.isBlank(line.getLineId())) continue;
                boolean bound = line.getBindingStatus() == OrderLineBindingStatus.BOUND
                        && StringUtils.isNotBlank(line.getTangbuySkuId());
                BigDecimal lineAmt = line.getPrice() == null || line.getQuantity() == null
                        ? BigDecimal.ZERO
                        : line.getPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
                TDraftOrderLineDO lineDO = new TDraftOrderLineDO()
                        .setOrderId(orderId)
                        .setUserId(userId)
                        .setShopName(shopName)
                        .setStatus(DraftOrderItemEnum.AWAITING.getCode())
                        .setPurchaseAmount(lineAmt)
                        .setReturnAmount(BigDecimal.ZERO)
                        .setSkuId(line.getTangbuySkuId())
                        .setGoodsId(bound ? line.getTangbuySkuId() : null)
                        .setGoodsType(0)
                        .setGoodsName(line.getTitle())
                        .setGoodsImg(line.getImageUrl())
                        .setNums(line.getQuantity())
                        .setStockNums(0)
                        .setRefundNums(0)
                        .setPrice(line.getPrice())
                        .setDiscountAmount(BigDecimal.ZERO)
                        .setOuterLineId(line.getLineId())
                        .setOuterVariantId(line.getOuterVariantId())
                        .setDelFlag(0)
                        .setCreateTime(Instant.now())
                        .setUpdateTime(Instant.now());
                draftOrderLineMapper.insert(lineDO);

                if (bound) {
                    TOrderLinePurchaseDO purchaseDO = new TOrderLinePurchaseDO()
                            .setOrderLineId(lineDO.getId())
                            .setOrderId(orderId)
                            .setSkuId(line.getTangbuySkuId())
                            .setGoodsId(line.getTangbuySkuId())
                            .setGoodsName(line.getTitle())
                            .setGoodsImg(line.getImageUrl())
                            .setNums(line.getQuantity())
                            .setPrice(line.getPrice())
                            .setPurchaseAmount(lineAmt)
                            .setDiscountAmount(BigDecimal.ZERO)
                            .setProviderType("alibaba")
                            .setDataSource("sku-align")
                            .setThirdShopId(line.getTangbuyProductId())
                            .setThirdGoodsId(line.getTangbuyProductId())
                            .setDelFlag(0)
                            .setCreateTime(Instant.now())
                            .setUpdateTime(Instant.now());
                    orderLinePurchaseMapper.insert(purchaseDO);
                }
            }
        }
        log.info("Draft order created id={} shop={} outer={} lines={}",
                orderId, shopName, outerOrderId,
                CollectionUtils.size(externalOrder.getLines()));
        return orderId;
    }
}
