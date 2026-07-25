package com.tang.plugin.service.match.sku;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.service.catalog.TangbuyMallClient;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 1: validate offer_sku_id exists in the offer SKU matrix before persisting bindings.
 * Prefers Tangbuy itemGet (same as the browser picker) when a detail URL is available;
 * falls back to the 1688 cross-border detail API for legacy numeric offer ids.
 */
@Component
public class OfferSkuMatrixValidator {

    public static final String ERR_SKU_NOT_IN_MATRIX = "SKU_NOT_IN_MATRIX";

    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;
    @Resource
    private TangbuyMallClient tangbuyMallClient;

    public void assertSkuInOffer(String offerId, String skuId) {
        assertSkuInOffer(offerId, skuId, null);
    }

    public void assertSkuInOffer(String offerId, String skuId, String detailUrl) {
        if (StringUtils.isAnyBlank(offerId, skuId)) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX + ": offerId 与 skuId 均不能为空");
        }
        String trimmedSkuId = skuId.trim();
        String trimmedUrl = StringUtils.trimToNull(detailUrl);
        if (trimmedUrl != null && shouldPreferItemGet(trimmedUrl)) {
            assertSkuInItemGet(trimmedUrl, trimmedSkuId);
            return;
        }
        try {
            assertSkuIn1688(offerId.trim(), trimmedSkuId);
        } catch (CustomException e) {
            if (trimmedUrl != null && shouldRetryWithItemGet(e)) {
                assertSkuInItemGet(trimmedUrl, trimmedSkuId);
                return;
            }
            throw e;
        }
    }

    /** Human-readable spec label for a matrix skuId; null when not found. */
    public String resolveSkuSpecLabel(String offerId, String skuId) {
        return resolveSkuSpecLabel(offerId, skuId, null);
    }

    public String resolveSkuSpecLabel(String offerId, String skuId, String detailUrl) {
        if (StringUtils.isAnyBlank(offerId, skuId)) {
            return null;
        }
        String trimmedSkuId = skuId.trim();
        String trimmedUrl = StringUtils.trimToNull(detailUrl);
        if (trimmedUrl != null && shouldPreferItemGet(trimmedUrl)) {
            String fromItemGet = resolveItemGetSpecLabel(trimmedUrl, trimmedSkuId);
            if (StringUtils.isNotBlank(fromItemGet)) {
                return fromItemGet;
            }
        }
        try {
            OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId.trim(), "en");
            List<OfferSkuVO> skus = detail.getSkus();
            if (skus == null) {
                return null;
            }
            for (OfferSkuVO sku : skus) {
                if (sku == null || StringUtils.isBlank(sku.getSkuId())) {
                    continue;
                }
                if (trimmedSkuId.equals(sku.getSkuId().trim())
                        || trimmedSkuId.equals(String.valueOf(sku.getSkuId()).trim())) {
                    String label = SkuMatcher.specLabel(sku);
                    return StringUtils.isNotBlank(label) ? label.trim() : null;
                }
            }
        } catch (CustomException e) {
            if (trimmedUrl != null && shouldRetryWithItemGet(e)) {
                return resolveItemGetSpecLabel(trimmedUrl, trimmedSkuId);
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

    private void assertSkuIn1688(String offerId, String skuId) {
        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId, "en");
        List<OfferSkuVO> skus = detail.getSkus();
        if (skus == null || skus.isEmpty()) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX + ": 货源未返回可用 SKU 矩阵");
        }
        if (!containsSku(skus, skuId)) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX
                    + ": skuId " + skuId + " 不在货源规格表中，请重新选择");
        }
    }

    private void assertSkuInItemGet(String detailUrl, String skuId) {
        JSONArray skus = tangbuyMallClient.itemGetProductSkus(detailUrl);
        if (skus == null || skus.isEmpty()) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX + ": itemGet 未返回可用 SKU 矩阵");
        }
        if (!containsItemGetSku(skus, skuId)) {
            throw new CustomException(ERR_SKU_NOT_IN_MATRIX
                    + ": skuId " + skuId + " 不在 itemGet 规格表中，请重新选择");
        }
    }

    private String resolveItemGetSpecLabel(String detailUrl, String skuId) {
        JSONArray skus = tangbuyMallClient.itemGetProductSkus(detailUrl);
        if (skus == null) {
            return null;
        }
        for (int i = 0; i < skus.size(); i++) {
            JSONObject sku = skus.getJSONObject(i);
            if (sku == null) {
                continue;
            }
            String id = sku.getString("skuId");
            if (StringUtils.isBlank(id)) {
                continue;
            }
            if (!skuId.equals(id.trim()) && !skuId.equals(String.valueOf(id).trim())) {
                continue;
            }
            String label = itemGetSpecLabel(sku.getJSONArray("skuAttributes"));
            return StringUtils.isNotBlank(label) ? label.trim() : null;
        }
        return null;
    }

    private static boolean shouldPreferItemGet(String detailUrl) {
        String lower = detailUrl.toLowerCase();
        return lower.contains("tangbuy.cc") || lower.contains("dropshipping.tangbuy");
    }

    private static boolean shouldRetryWithItemGet(CustomException e) {
        String msg = StringUtils.defaultString(e.getMessage());
        return msg.startsWith(Crossborder1688ProductClient.ERR_GATEWAY_BUSY)
                || msg.startsWith(Crossborder1688ProductClient.ERR_CRED_MISSING)
                || msg.startsWith(Crossborder1688ProductClient.ERR_TOKEN_INVALID)
                || msg.startsWith(Crossborder1688ProductClient.ERR_OFFER_NOT_FOUND);
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

    private static boolean containsItemGetSku(JSONArray skus, String skuId) {
        for (int i = 0; i < skus.size(); i++) {
            JSONObject sku = skus.getJSONObject(i);
            if (sku == null) {
                continue;
            }
            String id = sku.getString("skuId");
            if (StringUtils.isBlank(id)) {
                continue;
            }
            if (skuId.equals(id.trim()) || skuId.equals(String.valueOf(id).trim())) {
                return true;
            }
        }
        return false;
    }

    private static String itemGetSpecLabel(JSONArray attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < attrs.size(); i++) {
            JSONObject attr = attrs.getJSONObject(i);
            if (attr == null) {
                continue;
            }
            String value = StringUtils.firstNonBlank(attr.getString("attrValueTrans"), attr.getString("attrValue"));
            if (StringUtils.isBlank(value) || seen.contains(value.trim())) {
                continue;
            }
            seen.add(value.trim());
            parts.add(value.trim());
        }
        return parts.isEmpty() ? null : String.join(" / ", parts);
    }
}
