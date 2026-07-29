package com.tang.plugin.service.order.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.component.RedisManager;
import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TDraftOrderPackageDO;
import com.tang.plugin.domain.entity.order.TDraftOrderRefundLineDO;
import com.tang.plugin.domain.entity.order.TLogisticsFeeRecordDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.domain.entity.order.TOrderLineRefundItemDO;
import com.tang.plugin.domain.entity.order.TPackageLogisticsTrackDO;
import com.tang.plugin.domain.query.order.DraftOrderFillReq;
import com.tang.plugin.domain.query.order.DraftOrderPurchaseReq;
import com.tang.plugin.domain.query.order.DraftOrderRefundReq;
import com.tang.plugin.domain.vo.order.CreateDraftOrderPurchaseVO;
import com.tang.plugin.domain.vo.order.DraftOrderFillVO;
import com.tang.plugin.domain.vo.order.DraftOrderPackageDetailVO;
import com.tang.plugin.domain.vo.order.DraftOrderPurchaseAmountVO;
import com.tang.plugin.enums.order.DraftOrderItemEnum;
import com.tang.plugin.enums.order.PluginOrderTypeEnum;
import com.tang.plugin.mapper.order.TDraftOrderLineMapper;
import com.tang.plugin.mapper.order.TDraftOrderMapper;
import com.tang.plugin.mapper.order.TDraftOrderPackageMapper;
import com.tang.plugin.mapper.order.TDraftOrderRefundLineMapper;
import com.tang.plugin.mapper.order.TLogisticsFeeRecordMapper;
import com.tang.plugin.mapper.order.TOrderLinePurchaseMapper;
import com.tang.plugin.mapper.order.TOrderLineRefundItemMapper;
import com.tang.plugin.mapper.order.TPackageLogisticsTrackMapper;
import com.tang.plugin.mq.ProducerUtils;
import com.tang.plugin.service.order.DraftOrderManager;
import com.tang.plugin.service.order.DraftOrderPackageAmountManager;
import com.tang.plugin.service.order.InnerOrderSyncManager;
import com.tang.plugin.utils.OrderBizUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DraftOrderManagerImpl implements DraftOrderManager {

    @Resource private TDraftOrderMapper draftOrderMapper;
    @Resource private TDraftOrderLineMapper draftOrderLineMapper;
    @Resource private TOrderLinePurchaseMapper orderLinePurchaseMapper;
    @Resource private TDraftOrderPackageMapper draftOrderPackageMapper;
    @Resource private TLogisticsFeeRecordMapper logisticsFeeRecordMapper;
    @Resource private TDraftOrderRefundLineMapper draftOrderRefundLineMapper;
    @Resource private TOrderLineRefundItemMapper orderLineRefundItemMapper;
    @Resource private TPackageLogisticsTrackMapper packageLogisticsTrackMapper;
    @Resource private InnerOrderSyncManager innerOrderSyncManager;
    @Resource private DraftOrderPackageAmountManager packageAmountManager;
    @Resource private RedisManager redisManager;
    @Resource private ProducerUtils producerUtils;

    @Override
    public CreateDraftOrderPurchaseVO purchaseOrder(Long userId, DraftOrderPurchaseReq req) {
        if (req == null) throw new CustomException("req required");
        Integer type = req.getOrderType() == null ? PluginOrderTypeEnum.EXTERNAL_PULL.getCode() : req.getOrderType();
        if (!Objects.equals(type, PluginOrderTypeEnum.EXTERNAL_PULL.getCode())) {
            throw new CustomException("only dropship orderType=1 supported");
        }

        TDraftOrderDO order = resolveOrder(req);
        if (!Objects.equals(order.getStatus(), DraftOrderItemEnum.AWAITING.getCode())) {
            // Idempotent: already purchased
            if (StringUtils.isNotBlank(order.getTradeNo()) || StringUtils.isNotBlank(order.getPayNo())) {
                return new CreateDraftOrderPurchaseVO()
                        .setOrderId(order.getId())
                        .setOuterOrderId(order.getOuterOrderId())
                        .setTradeNo(StringUtils.defaultIfBlank(order.getTradeNo(), order.getPayNo()))
                        .setExpireTime(order.getExpireTime() == null ? null : Instant.ofEpochMilli(order.getExpireTime()))
                        .setType("dropship")
                        .setTangbuyOrderNo(null)
                        .setPayableAmountCny(order.getPurchaseAmount())
                        .setLineNos(listItemNos(order.getId()));
            }
            throw new CustomException("order status can not be purchase");
        }

        String lockKey = OrderBizUtils.operationLockKey(
                StringUtils.defaultIfBlank(order.getOuterOrderId(), String.valueOf(order.getId())));

        return redisManager.lockAround(lockKey, 3000, -1, () -> {
            // re-read under lock
            TDraftOrderDO fresh = draftOrderMapper.selectById(order.getId());
            if (fresh != null && StringUtils.isNotBlank(fresh.getTradeNo())) {
                return new CreateDraftOrderPurchaseVO()
                        .setOrderId(fresh.getId())
                        .setOuterOrderId(fresh.getOuterOrderId())
                        .setTradeNo(fresh.getTradeNo())
                        .setExpireTime(fresh.getExpireTime() == null ? null : Instant.ofEpochMilli(fresh.getExpireTime()))
                        .setType("dropship")
                        .setPayableAmountCny(fresh.getPurchaseAmount())
                        .setLineNos(listItemNos(fresh.getId()));
            }

            List<TDraftOrderLineDO> lines = draftOrderLineMapper.selectList(
                    new LambdaQueryWrapper<TDraftOrderLineDO>()
                            .eq(TDraftOrderLineDO::getOrderId, order.getId())
                            .eq(TDraftOrderLineDO::getDelFlag, 0)
                            .eq(TDraftOrderLineDO::getStatus, DraftOrderItemEnum.AWAITING.getCode()));
            List<TDraftOrderLineDO> valid = lines.stream()
                    .filter(l -> StringUtils.isNotBlank(l.getGoodsId()))
                    .collect(Collectors.toList());
            if (valid.isEmpty()) {
                throw new CustomException("pls match goods.");
            }

            UniOrderCreateResDTO res = innerOrderSyncManager.uniOrderByLines(
                    req, userId, valid, req.getPackageCreateInfo());

            return new CreateDraftOrderPurchaseVO()
                    .setOrderId(order.getId())
                    .setOuterOrderId(order.getOuterOrderId())
                    .setTradeNo(res.getTradeNo())
                    .setExpireTime(res.getExpireTime())
                    .setType(res.getType())
                    .setTangbuyOrderNo(res.getOrderNo())
                    .setPayableAmountCny(res.getTotalAmount() != null ? res.getTotalAmount() : order.getPurchaseAmount())
                    .setLineNos(new ArrayList<>(res.getOrderNoMap() == null ? List.of() : res.getOrderNoMap().values()));
        });
    }

    @Override
    public DraftOrderPurchaseAmountVO calDraftPurchasedOrderAmount(Long userId, DraftOrderPurchaseReq req) {
        TDraftOrderDO order = resolveOrder(req);
        return packageAmountManager.cal(order, req.getPackageCreateInfo());
    }

    @Override
    public DraftOrderFillVO fillPackageAmount(DraftOrderFillReq req) {
        if (req == null || req.getOrderId() == null || req.getAmount() == null) {
            throw new CustomException("orderId and amount required");
        }
        String payTradeNo = "FILL" + System.currentTimeMillis();
        TLogisticsFeeRecordDO record = new TLogisticsFeeRecordDO()
                .setOrderId(req.getOrderId())
                .setPackageId(req.getPackageId())
                .setFeeType("FILL")
                .setAmount(req.getAmount())
                .setPayTradeNo(payTradeNo)
                .setStatus(0)
                .setCreateTime(Instant.now())
                .setUpdateTime(Instant.now());
        logisticsFeeRecordMapper.insert(record);
        if (req.getPackageId() != null) {
            TDraftOrderPackageDO pkg = draftOrderPackageMapper.selectById(req.getPackageId());
            if (pkg != null) {
                BigDecimal fill = pkg.getFillAmount() == null ? BigDecimal.ZERO : pkg.getFillAmount();
                pkg.setFillAmount(fill.add(req.getAmount()));
                pkg.setUpdateTime(Instant.now());
                draftOrderPackageMapper.updateById(pkg);
            }
        }
        producerUtils.sendRepairFeeEvent(req.getOrderId(), payTradeNo);
        return new DraftOrderFillVO().setPayTradeNo(payTradeNo).setFeeRecordId(record.getId());
    }

    @Override
    public DraftOrderPackageDetailVO packageInfo(Long orderId) {
        if (orderId == null) throw new CustomException("orderId required");
        TDraftOrderPackageDO pkg = draftOrderPackageMapper.selectOne(
                new LambdaQueryWrapper<TDraftOrderPackageDO>()
                        .eq(TDraftOrderPackageDO::getOrderId, orderId)
                        .eq(TDraftOrderPackageDO::getDelFlag, 0)
                        .orderByDesc(TDraftOrderPackageDO::getId)
                        .last("LIMIT 1"));
        DraftOrderPackageDetailVO vo = new DraftOrderPackageDetailVO().setOrderPackage(pkg);
        if (pkg != null) {
            vo.setTracks(packageLogisticsTrackMapper.selectList(
                    new LambdaQueryWrapper<TPackageLogisticsTrackDO>()
                            .eq(TPackageLogisticsTrackDO::getPackageId, pkg.getId())
                            .orderByAsc(TPackageLogisticsTrackDO::getTrackTime)));
        }
        return vo;
    }

    @Override
    public void refundOrderLine(Long userId, DraftOrderRefundReq req) {
        if (req == null || req.getOrderId() == null) {
            throw new CustomException("orderId required");
        }
        String refundNo = "RF" + System.currentTimeMillis();
        TDraftOrderRefundLineDO refundLine = new TDraftOrderRefundLineDO()
                .setRefundNo(refundNo)
                .setOrderId(req.getOrderId())
                .setOrderLineId(req.getOrderLineId())
                .setRefundNum(req.getRefundNum())
                .setRefundAmount(req.getRefundAmount())
                .setRefundStatus(1)
                .setDelFlag(0)
                .setCreateTime(Instant.now())
                .setUpdateTime(Instant.now());
        draftOrderRefundLineMapper.insert(refundLine);

        if (req.getOrderLineId() != null) {
            TOrderLineRefundItemDO item = new TOrderLineRefundItemDO()
                    .setRefundId(refundLine.getId())
                    .setOrderLineId(req.getOrderLineId())
                    .setRefundNum(req.getRefundNum())
                    .setRefundAmount(req.getRefundAmount())
                    .setRefundReason(req.getReason())
                    .setRefundStatus(1)
                    .setCreateTime(Instant.now())
                    .setUpdateTime(Instant.now());
            orderLineRefundItemMapper.insert(item);
        }
        producerUtils.sendRefundEvent(req.getOrderId(), refundNo);
    }

    private TDraftOrderDO resolveOrder(DraftOrderPurchaseReq req) {
        if (req.getOrderId() != null) {
            TDraftOrderDO order = draftOrderMapper.selectById(req.getOrderId());
            if (order == null || Objects.equals(order.getDelFlag(), 1)) {
                throw new CustomException("order not exist");
            }
            return order;
        }
        if (StringUtils.isAnyBlank(req.getShopName(), req.getOuterOrderId())) {
            throw new CustomException("orderId or shopName+outerOrderId required");
        }
        TDraftOrderDO order = draftOrderMapper.selectOne(new LambdaQueryWrapper<TDraftOrderDO>()
                .eq(TDraftOrderDO::getShopName, req.getShopName())
                .eq(TDraftOrderDO::getOuterOrderId, req.getOuterOrderId())
                .eq(TDraftOrderDO::getDelFlag, 0)
                .last("LIMIT 1"));
        if (order == null) throw new CustomException("order not exist");
        return order;
    }

    private List<String> listItemNos(Long orderId) {
        return orderLinePurchaseMapper.selectList(new LambdaQueryWrapper<TOrderLinePurchaseDO>()
                        .eq(TOrderLinePurchaseDO::getOrderId, orderId)
                        .eq(TOrderLinePurchaseDO::getDelFlag, 0))
                .stream()
                .map(TOrderLinePurchaseDO::getItemNo)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }
}
