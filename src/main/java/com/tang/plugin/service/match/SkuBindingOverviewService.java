package com.tang.plugin.service.match;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.SkuVariantBindingVO;
import com.tang.plugin.domain.dto.match.SkuVariantVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.image.ImageMatchReason;
import com.tang.plugin.service.match.sku.SkuMatchReason;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S1-a: read-only SKU binding overview. Aggregates products that have at least one ACTIVE binding
 * (confirmed on the selection page) and expands each into its Shopify variants, echoing the current
 * per-variant binding state. No persistence, no auto-align (that is S1-b).
 */
@Slf4j
@Service
public class SkuBindingOverviewService {

    private static final String DEFAULT_OPTION_LABEL = "默认规格";

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;

    public List<SkuProductOverviewVO> overview(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("overview requires shopName");
        }
        List<ShopProductBinding> bindings = shopProductBindingRepository.listActiveByShop(shopName);
        if (bindings.isEmpty()) {
            return List.of();
        }

        // Index ACTIVE bindings by variant GID, and collect the owning product item ids (resolving
        // legacy bindings that lack a recorded item id via the SKU mirror).
        Map<String, ShopProductBinding> bindingBySkuId = new HashMap<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (ShopProductBinding b : bindings) {
            if (StringUtils.isNotBlank(b.getThirdPlatformSkuId())) {
                bindingBySkuId.putIfAbsent(b.getThirdPlatformSkuId(), b);
            }
            String itemId = b.getThirdPlatformItemId();
            if (StringUtils.isBlank(itemId)) {
                itemId = thirdPlatformSkuRepository
                        .findItemIdBySkuId(shopName, b.getThirdPlatformSkuId())
                        .orElse(null);
            }
            if (StringUtils.isNotBlank(itemId)) {
                itemIds.add(itemId);
            }
        }

        Map<String, ThirdPlatformProduct> productByItemId = new HashMap<>();
        for (ThirdPlatformProduct p : thirdPlatformProductRepository.listByShop(shopName)) {
            productByItemId.put(p.getThirdPlatformItemId(), p);
        }
        Map<Long, ShopProductMatchCandidate> candidateCache = new HashMap<>();

        List<SkuProductOverviewVO> result = new ArrayList<>();
        for (String itemId : itemIds) {
            ThirdPlatformProduct product = productByItemId.get(itemId);
            List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, itemId);
            List<SkuVariantVO> variantVos = new ArrayList<>();
            for (ThirdPlatformSku sku : variants) {
                variantVos.add(toVariantVO(sku, bindingBySkuId.get(sku.getThirdPlatformSkuId()), candidateCache));
            }
            result.add(new SkuProductOverviewVO()
                    .setThirdPlatformItemId(itemId)
                    .setTitle(product != null ? product.getTitle() : null)
                    .setImageUrl(product != null ? product.getPrimaryImageUrl() : null)
                    .setVariants(variantVos));
        }
        return result;
    }

    private SkuVariantVO toVariantVO(ThirdPlatformSku sku, ShopProductBinding binding,
                                     Map<Long, ShopProductMatchCandidate> candidateCache) {
        SkuVariantVO vo = new SkuVariantVO()
                .setThirdPlatformSkuId(sku.getThirdPlatformSkuId())
                .setSku(sku.getSku())
                .setOptionLabel(optionLabel(sku))
                .setPrice(sku.getPrice())
                .setImageUrl(sku.getImageUrl());
        if (binding != null) {
            vo.setBound(toBindingVO(binding, candidateCache));
        }
        return vo;
    }

    private SkuVariantBindingVO toBindingVO(ShopProductBinding binding,
                                            Map<Long, ShopProductMatchCandidate> candidateCache) {
        SkuVariantBindingVO vo = new SkuVariantBindingVO()
                .setBindingId(binding.getId())
                .setCandidateId(binding.getCandidateId())
                .setTangbuyProductId(binding.getTangbuyProductId())
                .setTangbuySkuId(binding.getTangbuySkuId());
        if (binding.getCandidateId() != null) {
            ShopProductMatchCandidate candidate = candidateCache.computeIfAbsent(
                    binding.getCandidateId(),
                    id -> shopProductMatchCandidateRepository.findById(id).orElse(null));
            if (candidate != null) {
                vo.setMatchScore(candidate.getMatchScore())
                        .setMatchSource(candidate.getMatchSource() == null ? null : candidate.getMatchSource().name());
                decodeReason(vo, candidate);
            }
        }
        return vo;
    }

    /**
     * Decode the candidate's structured audit reason by source: IMAGE (A3-2b) carries querySource/
     * appliedQuery/detailUrl; RULE/AI (S1-b1 auto-align) carries the matched spec + detailUrl.
     */
    private void decodeReason(SkuVariantBindingVO vo, ShopProductMatchCandidate candidate) {
        if (candidate.getMatchSource() == MatchSource.IMAGE) {
            ImageMatchReason.Decoded reason = ImageMatchReason.decode(candidate.getMatchReason());
            vo.setQuerySource(reason.querySource())
                    .setAppliedQuery(reason.appliedQuery())
                    .setDetailUrl(reason.detailUrl());
        } else {
            SkuMatchReason.Decoded reason = SkuMatchReason.decode(candidate.getMatchReason());
            vo.setTangbuySkuSpec(reason.specLabel())
                    .setDetailUrl(reason.detailUrl());
        }
    }

    /** Non-blank spec name: join present options, else fall back to sku, else a generic label. */
    private static String optionLabel(ThirdPlatformSku sku) {
        List<String> parts = new ArrayList<>();
        for (String opt : List.of(
                StringUtils.trimToEmpty(sku.getOption1()),
                StringUtils.trimToEmpty(sku.getOption2()),
                StringUtils.trimToEmpty(sku.getOption3()))) {
            if (StringUtils.isNotBlank(opt)) {
                parts.add(opt);
            }
        }
        if (!parts.isEmpty()) {
            return String.join(" / ", parts);
        }
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(sku.getSku()), DEFAULT_OPTION_LABEL);
    }
}
