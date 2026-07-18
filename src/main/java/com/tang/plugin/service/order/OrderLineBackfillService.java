package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.SkuBindingView;
import com.tang.plugin.domain.dto.order.BindingBackfillResult;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import com.tang.plugin.service.match.ProductBindingQueryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Manual binding backfill: flips historical UNBOUND order lines to BOUND using an existing ACTIVE
 * binding. Target tangbuy ids come strictly from the ACTIVE binding (never from callers). Explicit
 * capability only — not auto-triggered by binding creation. Does not touch business fields.
 */
@Slf4j
@Service
public class OrderLineBackfillService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;
    @Resource
    private ProductBindingQueryService productBindingQueryService;
    @Resource
    private TxManger txManger;

    /**
     * Backfill one persisted order line by its stable lineId.
     */
    public BindingBackfillResult backfillByLine(String shopName, String lineId) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("backfillByLine requires shopName and lineId");
        }
        ThirdPlatformOrderLine line = thirdPlatformOrderLineRepository.findByLineId(shopName, lineId)
                .orElseThrow(() -> new CustomException("Order line not found, shopName=" + shopName
                        + ", lineId=" + lineId));
        if (line.getDelFlag() != null && line.getDelFlag() != 0) {
            throw new CustomException("Order line is soft-deleted, backfill rejected, lineId=" + lineId);
        }

        BindingBackfillResult result = new BindingBackfillResult()
                .setShopName(shopName)
                .setVariantGid(line.getOuterVariantId());

        if (line.getBindingStatus() == OrderLineBindingStatus.BOUND) {
            result.setSkippedAlreadyBound(1);
            log.info("Backfill skip already BOUND shopName={} lineId={} outerOrderId={} backfilled=0",
                    shopName, lineId, line.getOuterOrderId());
            return result;
        }

        String variantGid = line.getOuterVariantId();
        if (StringUtils.isBlank(variantGid)) {
            throw new CustomException("Order line has blank outerVariantId, cannot backfill, lineId=" + lineId);
        }
        SkuBindingView binding = requireActiveBinding(shopName, variantGid);

        result.setMatched(1);
        int rows = txManger.run(() -> thirdPlatformOrderLineRepository.backfillByLine(
                shopName, lineId, binding.getTangbuyProductId(), binding.getTangbuySkuId()));
        result.setBackfilled(rows);
        log.info("Backfill by line done shopName={} variantGid={} lineId={} outerOrderId={} "
                        + "tangbuySkuId={} backfilled={}",
                shopName, variantGid, lineId, line.getOuterOrderId(), binding.getTangbuySkuId(), rows);
        return result;
    }

    /**
     * Backfill all active UNBOUND lines of a variant using its ACTIVE binding.
     */
    public BindingBackfillResult backfillByVariant(String shopName, String variantGid) {
        if (StringUtils.isAnyBlank(shopName, variantGid)) {
            throw new CustomException("backfillByVariant requires shopName and variantGid");
        }
        SkuBindingView binding = requireActiveBinding(shopName, variantGid);

        int matched = thirdPlatformOrderLineRepository
                .countByVariantAndBindingStatus(shopName, variantGid, OrderLineBindingStatus.UNBOUND);
        int skippedAlreadyBound = thirdPlatformOrderLineRepository
                .countByVariantAndBindingStatus(shopName, variantGid, OrderLineBindingStatus.BOUND);

        int backfilled = txManger.run(() -> thirdPlatformOrderLineRepository.backfillByVariant(
                shopName, variantGid, binding.getTangbuyProductId(), binding.getTangbuySkuId()));

        BindingBackfillResult result = new BindingBackfillResult()
                .setShopName(shopName)
                .setVariantGid(variantGid)
                .setMatched(matched)
                .setBackfilled(backfilled)
                .setSkippedAlreadyBound(skippedAlreadyBound);
        log.info("Backfill by variant done shopName={} variantGid={} tangbuySkuId={} matched={} "
                        + "backfilled={} skippedAlreadyBound={}",
                shopName, variantGid, binding.getTangbuySkuId(), matched, backfilled, skippedAlreadyBound);
        return result;
    }

    private SkuBindingView requireActiveBinding(String shopName, String variantGid) {
        return productBindingQueryService.findActiveSkuBinding(shopName, variantGid)
                .orElseThrow(() -> new CustomException("No ACTIVE binding for variant, cannot backfill, shopName="
                        + shopName + ", variantGid=" + variantGid));
    }
}
