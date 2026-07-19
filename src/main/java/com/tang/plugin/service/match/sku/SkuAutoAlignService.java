package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.SkuAutoAlignItemVO;
import com.tang.plugin.domain.dto.match.SkuAutoAlignResultVO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * S1-b1: auto-align a bound product's Shopify variants to the 1688 offer's SKU matrix, replacing the
 * A3-2b default-variant single binding with per-variant {@link MatchSource#RULE} bindings.
 *
 * <p>Reads the product-level offer from the existing ACTIVE binding (confirmed on the selection page),
 * fetches the offer's SKU matrix via {@link Crossborder1688ProductClient#queryProductDetail}, and matches
 * each variant with {@link SkuMatcher}. Confident matches are persisted in one transaction as a CONFIRMED
 * RULE candidate (carrying the overlap score + structured {@link SkuMatchReason}) plus an ACTIVE binding
 * sourced {@code AUTO_ALIGN}; unmatched variants are left untouched (no wrong auto-bind). Idempotent —
 * re-running rewrites the same anchored rows.
 */
@Slf4j
@Service
public class SkuAutoAlignService {

    public static final String ERR_NOT_BOUND = "NOT_BOUND";
    public static final String ERR_NO_VARIANT = "NO_VARIANT";
    public static final String ERR_NO_OFFER_SKU = "NO_OFFER_SKU";

    private static final String BIND_SOURCE_AUTO = "AUTO_ALIGN";
    private static final String ALGO_RULE = "RULE";
    private static final int SCORE_SCALE = 4;

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;
    @Resource
    private TxManger txManger;

    /**
     * Auto-align the variants of {@code thirdPlatformItemId}. When {@code offerId} is blank it is resolved
     * from the product's ACTIVE binding.
     *
     * @throws CustomException prefixed with {@link #ERR_NOT_BOUND} / {@link #ERR_NO_VARIANT} / {@link #ERR_NO_OFFER_SKU}.
     */
    public SkuAutoAlignResultVO autoAlign(String shopName, String thirdPlatformItemId, String offerId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformItemId)) {
            throw new CustomException("auto-align requires shopName and thirdPlatformItemId");
        }
        String resolvedOffer = StringUtils.isNotBlank(offerId)
                ? offerId.trim()
                : resolveBoundOffer(shopName, thirdPlatformItemId);

        List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, thirdPlatformItemId);
        if (variants.isEmpty()) {
            throw new CustomException(ERR_NO_VARIANT + ": 该商品无可用变体(SKU)，请重新同步商品后再对齐");
        }

        OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(resolvedOffer, "en");
        if (detail.getSkus() == null || detail.getSkus().isEmpty()) {
            throw new CustomException(ERR_NO_OFFER_SKU + ": 1688 offer(" + resolvedOffer + ") 未返回可用 SKU");
        }

        List<VariantAlignment> alignments = SkuMatcher.align(variants, detail.getSkus());
        String detailUrl = "https://detail.1688.com/offer/" + resolvedOffer + ".html";

        List<SkuAutoAlignItemVO> items = new ArrayList<>();
        int[] matched = {0};
        txManger.run(() -> {
            for (VariantAlignment a : alignments) {
                SkuAutoAlignItemVO item = new SkuAutoAlignItemVO()
                        .setThirdPlatformSkuId(a.variantGid())
                        .setOptionLabel(a.optionLabel())
                        .setMatched(a.matched())
                        .setScore(toScore(a.score()));
                if (a.matched()) {
                    item.setTangbuySkuId(a.skuId()).setTangbuySkuSpec(a.specLabel());
                    persist(shopName, thirdPlatformItemId, resolvedOffer, a, detailUrl);
                    matched[0]++;
                }
                items.add(item);
            }
            log.info("SkuAutoAlign shopName={} itemId={} offerId={} variants={} matched={}",
                    shopName, thirdPlatformItemId, resolvedOffer, alignments.size(), matched[0]);
        });

        return new SkuAutoAlignResultVO()
                .setThirdPlatformItemId(thirdPlatformItemId)
                .setOfferId(resolvedOffer)
                .setTotalVariants(variants.size())
                .setMatchedCount(matched[0])
                .setItems(items);
    }

    private void persist(String shopName, String itemId, String offerId, VariantAlignment a, String detailUrl) {
        String reason = SkuMatchReason.encode(ALGO_RULE, a.score(), a.skuId(), a.specLabel(), detailUrl);
        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(a.variantGid())
                .setTangbuyProductId(offerId)
                .setTangbuySkuId(a.skuId())
                .setMatchSource(MatchSource.RULE)
                .setMatchScore(toScore(a.score()))
                .setMatchReason(reason)
                .setStatus(MatchStatus.CONFIRMED);
        Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);

        ShopProductBinding binding = new ShopProductBinding()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(a.variantGid())
                .setTangbuyProductId(offerId)
                .setTangbuySkuId(a.skuId())
                .setBindSource(BIND_SOURCE_AUTO)
                .setCandidateId(candidateId)
                .setBindStatus(BindingStatus.ACTIVE);
        shopProductBindingRepository.upsertActive(binding);
    }

    /** The offer bound to any variant of this product (A3-2b product-level binding). */
    private String resolveBoundOffer(String shopName, String itemId) {
        return shopProductBindingRepository.listActiveByShop(shopName).stream()
                .filter(b -> itemId.equals(b.getThirdPlatformItemId()))
                .map(ShopProductBinding::getTangbuyProductId)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElseThrow(() -> new CustomException(ERR_NOT_BOUND
                        + ": 该商品尚未绑定 1688 货源，请先在「智能选品」确认匹配后再自动对齐"));
    }

    private static BigDecimal toScore(double score) {
        double clamped = Math.max(0d, Math.min(1d, score));
        return BigDecimal.valueOf(clamped).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
