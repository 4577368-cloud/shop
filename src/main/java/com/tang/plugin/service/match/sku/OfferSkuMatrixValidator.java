package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.service.match.sku.SkuMatcher;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 1: validate offer_sku_id exists in the offer SKU matrix before persisting bindings.
 */
@Component
public class OfferSkuMatrixValidator {

    public static final String ERR_SKU_NOT_IN_MATRIX = "SKU_NOT_IN_MATRIX";

    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;

    public void assertSkuInOffer(String offerId, String skuId) {
        if (StringUtils.isAnyBlank(offerId, skuId)) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX + ": offerId 与 skuId 均不能为空");
        }
        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId.trim(), "en");
        List<OfferSkuVO> skus = detail.getSkus();
        if (skus == null || skus.isEmpty()) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX + ": 货源未返回可用 SKU 矩阵");
        }
        if (!containsSku(skus, skuId.trim())) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX
                    + ": skuId " + skuId.trim() + " 不在货源规格表中，请重新选择");
        }
    }

    /** Human-readable spec label for a matrix skuId; null when not found. */
    public String resolveSkuSpecLabel(String offerId, String skuId) {
        if (StringUtils.isAnyBlank(offerId, skuId)) {
            return null;
        }
        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId.trim(), "en");
        List<OfferSkuVO> skus = detail.getSkus();
        if (skus == null) {
            return null;
        }
        for (OfferSkuVO sku : skus) {
            if (sku == null || StringUtils.isBlank(sku.getSkuId())) {
                continue;
            }
            if (skuId.trim().equals(sku.getSkuId().trim())
                    || skuId.trim().equals(String.valueOf(sku.getSkuId()).trim())) {
                String label = SkuMatcher.specLabel(sku);
                return StringUtils.isNotBlank(label) ? label.trim() : null;
            }
        }
        return null;
    }

    /** Resolve default matrix sku when image confirm omitted offerSkuId. */
    public String resolveDefaultSkuId(String offerId) {
        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId.trim(), "en");
        List<OfferSkuVO> skus = detail.getSkus();
        if (skus == null || skus.isEmpty()) {
            return null;
        }
        for (OfferSkuVO sku : skus) {
            if (StringUtils.isNotBlank(sku.getSkuId())) {
                return sku.getSkuId().trim();
            }
        }
        return null;
    }

    private static boolean containsSku(List<OfferSkuVO> skus, String skuId) {
        for (OfferSkuVO sku : skus) {
            if (sku == null || StringUtils.isBlank(sku.getSkuId())) {
                continue;
            }
            if (skuId.equals(sku.getSkuId().trim()) || skuId.equals(String.valueOf(sku.getSkuId()).trim())) {
                return true;
            }
        }
        return false;
    }
}
