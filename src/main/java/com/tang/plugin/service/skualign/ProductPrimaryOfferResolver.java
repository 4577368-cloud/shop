package com.tang.plugin.service.skualign;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.skualign.ProductSourceBinding;
import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.skualign.ProductSourceBindingRepository;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import com.tang.plugin.service.match.sku.SkuAutoAlignService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the authoritative primary 1688 offer for a Shopify product.
 * Never infer primary from the newest per-variant binding (supplement rows sort last).
 */
@Component
public class ProductPrimaryOfferResolver {

    @Resource
    private ProductSourceBindingRepository productSourceRepository;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private VariantSkuBindingRepository v1BindingRepository;

    public String resolvePrimaryOffer(String shopName, String productId) {
        if (StringUtils.isAnyBlank(shopName, productId)) {
            throw new CustomException(SkuAutoAlignService.ERR_NOT_BOUND + ": shopName and productId required");
        }

        Optional<ProductSourceBinding> psb = productSourceRepository.findByProduct(shopName, productId);
        if (psb.isPresent() && StringUtils.isNotBlank(psb.get().getPrimaryOfferId())) {
            return psb.get().getPrimaryOfferId().trim();
        }

        Map<String, VariantSkuBinding> v1ByVariant = v1BindingRepository.mapActiveByProduct(shopName, productId);
        Map<String, Integer> primaryCounts = new HashMap<>();
        for (VariantSkuBinding binding : v1ByVariant.values()) {
            if (binding.getSourceRole() == SourceRole.SUPPLEMENT) {
                continue;
            }
            if (StringUtils.isNotBlank(binding.getOfferId())) {
                primaryCounts.merge(binding.getOfferId().trim(), 1, Integer::sum);
            }
        }
        String fromV1 = modeOfferId(primaryCounts);
        if (fromV1 != null) {
            return fromV1;
        }

        Map<String, Integer> legacyCounts = new HashMap<>();
        for (ShopProductBinding binding : shopProductBindingRepository.listBindableByShop(shopName)) {
            if (!productId.equals(binding.getThirdPlatformItemId())) {
                continue;
            }
            VariantSkuBinding v1 = v1ByVariant.get(binding.getThirdPlatformSkuId());
            if (v1 != null && v1.getSourceRole() == SourceRole.SUPPLEMENT) {
                continue;
            }
            if (StringUtils.isNotBlank(binding.getTangbuyProductId())) {
                legacyCounts.merge(binding.getTangbuyProductId().trim(), 1, Integer::sum);
            }
        }
        String fromLegacy = modeOfferId(legacyCounts);
        if (fromLegacy != null) {
            return fromLegacy;
        }

        throw new CustomException(SkuAutoAlignService.ERR_NOT_BOUND
                + ": 该商品尚未绑定货源，请先确认匹配");
    }

    private static String modeOfferId(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }
}
