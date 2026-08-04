package com.tang.plugin.service.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.mapper.order.TDraftOrderLineMapper;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mapper.order.TOrderLinePurchaseMapper;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import com.tang.plugin.repository.ThirdPlatformOrderRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * After SKU binding / backfill, sync BOUND third-platform lines into t_draft_order_line
 * (goodsId) and t_order_line_purchase so purchaseOrder can proceed.
 */
@Slf4j
@Service
public class DraftOrderBindingSyncService {

    @Resource private ThirdPlatformOrderRepository thirdPlatformOrderRepository;
    @Resource private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;
    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private TDraftOrderLineMapper draftOrderLineMapper;
    @Resource private TOrderLinePurchaseMapper orderLinePurchaseMapper;

    public int syncOrder(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) return 0;
        Optional<ThirdPlatformOrder> headerOpt =
                thirdPlatformOrderRepository.findByOuterOrderId(shopName, outerOrderId);
        if (headerOpt.isEmpty() || headerOpt.get().getDraftOrderId() == null) {
            return 0;
        }
        Long draftId = headerOpt.get().getDraftOrderId();
        TDraftOrderDO draft = draftOrderMapper.selectById(draftId);
        if (draft == null || Integer.valueOf(1).equals(draft.getDelFlag())) return 0;
        if (!Integer.valueOf(DraftOrderItemEnum.AWAITING.getCode()).equals(draft.getStatus())
                && draft.getStatus() != null
                && draft.getStatus() > DraftOrderItemEnum.AWAITING.getCode()) {
            // Already purchased — do not rewrite purchase rows
            log.debug("Draft binding sync skip non-awaiting draftId={} status={}", draftId, draft.getStatus());
        }

        int updated = 0;
        List<ThirdPlatformOrderLine> lines =
                thirdPlatformOrderLineRepository.listByOrder(shopName, outerOrderId);
        for (ThirdPlatformOrderLine tpLine : lines) {
            if (tpLine == null || tpLine.getBindingStatus() != OrderLineBindingStatus.BOUND) continue;
            if (StringUtils.isBlank(tpLine.getTangbuySkuId())) continue;
            if (StringUtils.isBlank(tpLine.getLineId())) continue;

            TDraftOrderLineDO draftLine = draftOrderLineMapper.selectOne(
                    new LambdaQueryWrapper<TDraftOrderLineDO>()
                            .eq(TDraftOrderLineDO::getOrderId, draftId)
                            .eq(TDraftOrderLineDO::getOuterLineId, tpLine.getLineId())
                            .eq(TDraftOrderLineDO::getDelFlag, 0)
                            .last("LIMIT 1"));
            if (draftLine == null) continue;

            if (StringUtils.isBlank(draftLine.getGoodsId())) {
                draftOrderLineMapper.update(null, new LambdaUpdateWrapper<TDraftOrderLineDO>()
                        .eq(TDraftOrderLineDO::getId, draftLine.getId())
                        .set(TDraftOrderLineDO::getGoodsId, tpLine.getTangbuySkuId())
                        .set(TDraftOrderLineDO::getSkuId, tpLine.getTangbuySkuId())
                        .set(TDraftOrderLineDO::getUpdateTime, Instant.now()));
                updated++;
            }

            Long purchaseCount = orderLinePurchaseMapper.selectCount(
                    new LambdaQueryWrapper<TOrderLinePurchaseDO>()
                            .eq(TOrderLinePurchaseDO::getOrderLineId, draftLine.getId())
                            .eq(TOrderLinePurchaseDO::getDelFlag, 0));
            if (purchaseCount == null || purchaseCount == 0) {
                BigDecimal lineAmt = draftLine.getPurchaseAmount() == null
                        ? BigDecimal.ZERO : draftLine.getPurchaseAmount();
                TOrderLinePurchaseDO purchase = new TOrderLinePurchaseDO()
                        .setOrderLineId(draftLine.getId())
                        .setOrderId(draftId)
                        .setSkuId(tpLine.getTangbuySkuId())
                        .setGoodsId(tpLine.getTangbuySkuId())
                        .setGoodsName(draftLine.getGoodsName())
                        .setGoodsImg(draftLine.getGoodsImg())
                        .setNums(draftLine.getNums())
                        .setPrice(draftLine.getPrice())
                        .setPurchaseAmount(lineAmt)
                        .setDiscountAmount(BigDecimal.ZERO)
                        .setProviderType("alibaba")
                        .setDataSource("sku-align")
                        .setThirdShopId(tpLine.getTangbuyProductId())
                        .setThirdGoodsId(tpLine.getTangbuyProductId())
                        .setDelFlag(0)
                        .setCreateTime(Instant.now())
                        .setUpdateTime(Instant.now());
                orderLinePurchaseMapper.insert(purchase);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("Draft binding sync shop={} outer={} draftId={} updates={}",
                    shopName, outerOrderId, draftId, updated);
        }
        return updated;
    }

    public int syncShopBoundLines(String shopName) {
        if (StringUtils.isBlank(shopName)) return 0;
        int total = 0;
        for (ThirdPlatformOrder h : thirdPlatformOrderRepository.listByShop(shopName)) {
            if (h == null || h.getDraftOrderId() == null) continue;
            total += syncOrder(shopName, h.getOuterOrderId());
        }
        return total;
    }
}
