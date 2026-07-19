package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.ImageBindingView;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A3-2b: confirm a chosen 1688 image-search offer into a SKU-level {@link ShopProductBinding} (route B).
 *
 * <p>Stateful, but strictly additive over A3-2a (which stays a read-only preview). The backend resolves
 * the Shopify variant GID from the local {@code third_platform_sku} mirror (no live Shopify call), then
 * in a single transaction writes an {@link MatchSource#IMAGE} candidate (directly CONFIRMED, carrying the
 * similarity as matchScore and an auditable structured matchReason) and an ACTIVE binding sourced
 * {@code FROM_CANDIDATE}. Re-confirming a different offer for the same variant is a rebind: the old
 * ACTIVE binding is deactivated first and the change is logged (REBOUND).
 */
@Slf4j
@Service
public class ImageMatchConfirmService {

    public static final String ERR_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";
    public static final String ERR_NO_VARIANT = "NO_VARIANT";

    private static final String BIND_SOURCE_FROM_CANDIDATE = "FROM_CANDIDATE";
    /** matchScore column is DECIMAL(6,4). */
    private static final int SCORE_SCALE = 4;
    private static final BigDecimal SCORE_MAX = new BigDecimal("99.9999");

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private TxManger txManger;

    /**
     * Confirm the chosen offer as the ACTIVE binding of the product's default variant.
     *
     * @throws CustomException prefixed with {@link #ERR_PRODUCT_NOT_FOUND} / {@link #ERR_NO_VARIANT}.
     */
    public ImageBindingView confirm(ConfirmImageMatchDTO dto) {
        if (dto == null
                || StringUtils.isAnyBlank(dto.getShopName(), dto.getThirdPlatformItemId(), dto.getOfferProductId())) {
            throw new CustomException("confirm requires shopName, thirdPlatformItemId and offerProductId");
        }
        String shopName = dto.getShopName();
        String itemId = dto.getThirdPlatformItemId();

        requireProductExists(shopName, itemId);
        String variantGid = resolveDefaultVariantGid(shopName, itemId);

        String tangbuyProductId = dto.getOfferProductId();
        String tangbuySkuId = StringUtils.firstNonBlank(dto.getOfferSkuId(), dto.getOfferProductId());
        BigDecimal matchScore = toScore(dto.getSimilarityScore());
        String matchReason = ImageMatchReason.encode(
                dto.getImageSource(), dto.getQuerySource(), dto.getAppliedQuery(), dto.getDetailUrl());

        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(variantGid)
                .setTangbuyProductId(tangbuyProductId)
                .setTangbuySkuId(tangbuySkuId)
                .setMatchSource(MatchSource.IMAGE)
                .setMatchScore(matchScore)
                .setMatchReason(matchReason)
                .setStatus(MatchStatus.CONFIRMED);

        txManger.run(() -> {
            Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);
            String oldTangbuySkuId = currentBoundTangbuySkuId(shopName, variantGid);
            shopProductBindingRepository.deactivateBySkuId(shopName, variantGid);
            ShopProductBinding binding = new ShopProductBinding()
                    .setShopName(shopName)
                    .setShopType(PluginType.SHOPIFY.getCode())
                    .setThirdPlatformItemId(itemId)
                    .setThirdPlatformSkuId(variantGid)
                    .setTangbuyProductId(tangbuyProductId)
                    .setTangbuySkuId(tangbuySkuId)
                    .setBindSource(BIND_SOURCE_FROM_CANDIDATE)
                    .setCandidateId(candidateId)
                    .setBindStatus(BindingStatus.ACTIVE);
            shopProductBindingRepository.upsertActive(binding);
            boolean rebind = oldTangbuySkuId != null && !oldTangbuySkuId.equals(tangbuySkuId);
            log.info("ImageMatch {} shopName={} itemId={} variantGid={} tangbuySkuId: {} -> {} candidateId={} score={}",
                    rebind ? "REBOUND" : "SET", shopName, itemId, variantGid,
                    oldTangbuySkuId, tangbuySkuId, candidateId, matchScore);
        });

        return view(itemId, variantGid, tangbuyProductId, tangbuySkuId, matchScore,
                ImageMatchReason.decode(matchReason));
    }

    /**
     *回显: all ACTIVE image-search bindings of a shop, decoded for display. Read-only; empty is normal.
     */
    public List<ImageBindingView> listActiveBindings(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("listActiveBindings requires shopName");
        }
        List<ImageBindingView> views = new ArrayList<>();
        for (ShopProductBinding binding : shopProductBindingRepository.listActiveByShop(shopName)) {
            BigDecimal score = null;
            ImageMatchReason.Decoded reason = ImageMatchReason.decode(null);
            if (binding.getCandidateId() != null) {
                Optional<ShopProductMatchCandidate> candidate =
                        shopProductMatchCandidateRepository.findById(binding.getCandidateId());
                if (candidate.isPresent()) {
                    score = candidate.get().getMatchScore();
                    reason = ImageMatchReason.decode(candidate.get().getMatchReason());
                }
            }
            views.add(view(binding.getThirdPlatformItemId(),
                    binding.getThirdPlatformSkuId(), binding.getTangbuyProductId(), binding.getTangbuySkuId(),
                    score, reason));
        }
        return views;
    }

    private void requireProductExists(String shopName, String itemId) {
        boolean exists = thirdPlatformProductRepository.listByShop(shopName).stream()
                .map(ThirdPlatformProduct::getThirdPlatformItemId)
                .anyMatch(itemId::equals);
        if (!exists) {
            throw new CustomException(ERR_PRODUCT_NOT_FOUND
                    + ": 未找到店铺商品(" + shopName + "/" + itemId + ")，请先同步商品");
        }
    }

    private String resolveDefaultVariantGid(String shopName, String itemId) {
        return thirdPlatformSkuRepository.findFirstByItem(shopName, itemId)
                .map(ThirdPlatformSku::getThirdPlatformSkuId)
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new CustomException(ERR_NO_VARIANT
                        + ": 该商品无可用变体(SKU)，请重新同步商品后再匹配"));
    }

    private String currentBoundTangbuySkuId(String shopName, String variantGid) {
        return shopProductBindingRepository.findActiveBySkuId(shopName, variantGid)
                .map(ShopProductBinding::getTangbuySkuId)
                .orElse(null);
    }

    private static BigDecimal toScore(Double similarityScore) {
        if (similarityScore == null || similarityScore.isNaN() || similarityScore < 0) {
            return BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal score = BigDecimal.valueOf(similarityScore).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        return score.compareTo(SCORE_MAX) > 0 ? SCORE_MAX : score;
    }

    private static ImageBindingView view(String itemId, String variantGid,
                                         String tangbuyProductId, String tangbuySkuId, BigDecimal score,
                                         ImageMatchReason.Decoded reason) {
        return new ImageBindingView()
                .setBound(true)
                .setThirdPlatformItemId(itemId)
                .setThirdPlatformSkuId(variantGid)
                .setTangbuyProductId(tangbuyProductId)
                .setTangbuySkuId(tangbuySkuId)
                .setMatchScore(score)
                .setImageSource(reason.imageSource())
                .setQuerySource(reason.querySource())
                .setAppliedQuery(reason.appliedQuery())
                .setDetailUrl(reason.detailUrl());
    }
}
