package com.tang.plugin.service.match;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.SkuVariantBindingVO;
import com.tang.plugin.domain.dto.match.SkuVariantVO;
import com.tang.plugin.domain.entity.skualign.VariantSkuBinding;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.VariantBindingState;
import com.tang.plugin.repository.skualign.VariantSkuBindingRepository;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.image.ImageMatchReason;
import com.tang.plugin.service.match.sku.SkuMatchReason;
import com.tang.plugin.utils.CdnThumbUrl;
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
    @Resource
    private VariantSkuBindingRepository variantSkuBindingRepository;

    public List<SkuProductOverviewVO> overview(String shopName) {
        return overview(shopName, null, false);
    }

    /**
     * @param thumbWidth when set, downscales product/variant (and offer) image URLs for list views
     * @param compact    omits heavy IMAGE snapshot fields from bindings to shrink JSON
     */
    public List<SkuProductOverviewVO> overview(String shopName, Integer thumbWidth, boolean compact) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("overview requires shopName");
        }
        List<ShopProductBinding> bindings = shopProductBindingRepository.listBindableByShop(shopName);
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
            if (product == null) {
                // Orphan binding: product mirror was deleted in Shopify; skip until cleanup runs.
                continue;
            }
            List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, itemId);
            Map<String, VariantSkuBinding> v1BySku =
                    variantSkuBindingRepository.mapActiveByProduct(shopName, itemId);
            List<SkuVariantVO> variantVos = new ArrayList<>();
            for (ThirdPlatformSku sku : variants) {
                variantVos.add(toVariantVO(sku, bindingBySkuId.get(sku.getThirdPlatformSkuId()),
                        v1BySku.get(sku.getThirdPlatformSkuId()), candidateCache));
            }
            String tangbuyProductId = null;
            String detailUrl = null;
            for (SkuVariantVO v : variantVos) {
                if (v.getBound() == null) {
                    continue;
                }
                if (tangbuyProductId == null && StringUtils.isNotBlank(v.getBound().getTangbuyProductId())) {
                    tangbuyProductId = v.getBound().getTangbuyProductId();
                }
                if (detailUrl == null && StringUtils.isNotBlank(v.getBound().getDetailUrl())) {
                    detailUrl = v.getBound().getDetailUrl();
                }
            }
            result.add(new SkuProductOverviewVO()
                    .setThirdPlatformItemId(itemId)
                    .setTitle(product != null ? product.getTitle() : null)
                    .setImageUrl(product != null ? product.getPrimaryImageUrl() : null)
                    .setCurrency(product != null ? product.getCurrency() : null)
                    .setTangbuyProductId(tangbuyProductId)
                    .setDetailUrl(detailUrl)
                    .setVariants(variantVos));
        }
        return applyListPresentation(result, thumbWidth, compact);
    }

    private static List<SkuProductOverviewVO> applyListPresentation(
            List<SkuProductOverviewVO> result, Integer thumbWidth, boolean compact) {
        int px = thumbWidth != null && thumbWidth > 0 ? thumbWidth : 0;
        for (SkuProductOverviewVO p : result) {
            if (px > 0 && StringUtils.isNotBlank(p.getImageUrl())) {
                p.setImageUrl(CdnThumbUrl.apply(p.getImageUrl(), px));
            }
            if (p.getVariants() == null) {
                continue;
            }
            for (SkuVariantVO v : p.getVariants()) {
                if (px > 0 && StringUtils.isNotBlank(v.getImageUrl())) {
                    v.setImageUrl(CdnThumbUrl.apply(v.getImageUrl(), px));
                }
                SkuVariantBindingVO bound = v.getBound();
                if (bound == null) {
                    continue;
                }
                if (px > 0 && StringUtils.isNotBlank(bound.getOfferImageUrl())) {
                    bound.setOfferImageUrl(CdnThumbUrl.apply(bound.getOfferImageUrl(), px));
                }
                if (compact) {
                    bound.setOfferPrice(null);
                    bound.setQuerySource(null);
                    bound.setAppliedQuery(null);
                }
            }
        }
        return result;
    }

    private SkuVariantVO toVariantVO(ThirdPlatformSku sku, ShopProductBinding binding,
                                     VariantSkuBinding v1Binding,
                                     Map<Long, ShopProductMatchCandidate> candidateCache) {
        SkuVariantVO vo = new SkuVariantVO()
                .setThirdPlatformSkuId(sku.getThirdPlatformSkuId())
                .setSku(sku.getSku())
                .setOptionLabel(optionLabel(sku))
                .setPrice(sku.getPrice())
                .setImageUrl(sku.getImageUrl());
        if (binding != null) {
            vo.setBound(toBindingVO(binding, candidateCache));
        } else {
            SkuVariantBindingVO fromV1 = toBindingVoFromV1(v1Binding);
            if (fromV1 != null) {
                vo.setBound(fromV1);
            }
        }
        return vo;
    }

    /** V1 active bindings (auto-align / supplement manual) not yet mirrored in legacy table. */
    private static SkuVariantBindingVO toBindingVoFromV1(VariantSkuBinding v1) {
        if (v1 == null || !v1.isActive()) {
            return null;
        }
        if (v1.getBindingState() == VariantBindingState.BLOCKED) {
            return null;
        }
        if (StringUtils.isBlank(v1.getOfferSkuId())) {
            return null;
        }
        return new SkuVariantBindingVO()
                .setTangbuyProductId(v1.getOfferId())
                .setTangbuySkuId(v1.getOfferSkuId())
                .setBindStatus(com.tang.plugin.enums.match.BindingStatus.ACTIVE.name())
                .setMatchSource(v1.getMatchSource() != null ? v1.getMatchSource().name() : MatchSource.RULE.name());
    }

    private SkuVariantVO toVariantVO(ThirdPlatformSku sku, ShopProductBinding binding,
                                     Map<Long, ShopProductMatchCandidate> candidateCache) {
        return toVariantVO(sku, binding, null, candidateCache);
    }

    private SkuVariantBindingVO toBindingVO(ShopProductBinding binding,
                                            Map<Long, ShopProductMatchCandidate> candidateCache) {
        SkuVariantBindingVO vo = new SkuVariantBindingVO()
                .setBindingId(binding.getId())
                .setCandidateId(binding.getCandidateId())
                .setTangbuyProductId(binding.getTangbuyProductId())
                .setTangbuySkuId(binding.getTangbuySkuId())
                .setBindStatus(binding.getBindStatus() == null ? null : binding.getBindStatus().name());
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
                    .setDetailUrl(reason.detailUrl())
                    .setTangbuySkuSpec(StringUtils.firstNonBlank(reason.skuSpec(), reason.offerTitle()))
                    .setOfferImageUrl(reason.imageUrl())
                    .setOfferPrice(reason.price());
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
