package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.service.match.sku.Crossborder1688ProductClient;
import com.tang.plugin.service.match.sku.SkuMatcher;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * One-shot repair for legacy image bindings created before the candidate image/price snapshot existed
 * (their {@code match_reason} carries {@code img/qs/q/url} but no {@code pic}/{@code price}). Without a
 * snapshot the UI falls back to {@code queryProductDetail}, whose cross-border payload frequently has a
 * null white image and an empty SKU matrix — hence the "无图 / 成本待取" cards.
 *
 * <p>For each bound offer missing a snapshot, we recover the image + price and write them back into the
 * existing candidate's {@code match_reason} (score/status/offer choice untouched):
 * <ol>
 *   <li><b>Image search</b> (preferred): re-run the same shop-product image search and match the bound
 *       {@code offerId} in the results — this yields the very image/price the offer carried at match time.</li>
 *   <li><b>Offer detail</b> (fallback): otherwise derive a representative image (white image, else first
 *       per-SKU image) and price (min SKU price) from {@code queryProductDetail}.</li>
 * </ol>
 * Idempotent and fail-open: bindings that already have a snapshot are skipped; per-binding failures are
 * counted and never abort the run.
 */
@Slf4j
@Service
public class ImageBindingSnapshotBackfillService {

    /** How many candidates to pull when re-searching, to give the bound offer a chance to appear. */
    private static final int SEARCH_LIMIT = 20;
    private static final String COUNTRY = "en";

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ImageSearchService imageSearchService;
    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;

    public BackfillResult backfill(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("backfill requires shopName");
        }
        BackfillResult result = new BackfillResult();
        List<ShopProductBinding> bindings = shopProductBindingRepository.listBindableByShop(shopName);
        result.setTotal(bindings.size());

        for (ShopProductBinding binding : bindings) {
            if (binding.getCandidateId() == null) {
                result.skipped++;
                continue;
            }
            Optional<ShopProductMatchCandidate> found =
                    shopProductMatchCandidateRepository.findById(binding.getCandidateId());
            if (found.isEmpty()) {
                result.skipped++;
                continue;
            }
            ShopProductMatchCandidate candidate = found.get();
            ImageMatchReason.Decoded reason = ImageMatchReason.decode(candidate.getMatchReason());
            if (StringUtils.isNotBlank(StringUtils.firstNonBlank(reason.skuSpec(), reason.offerTitle()))
                    && reason.imageUrl() != null) {
                result.alreadyOk++;
                continue;
            }

            String offerId = binding.getTangbuyProductId();
            String skuId = binding.getTangbuySkuId();
            String itemId = binding.getThirdPlatformItemId();
            String image = reason.imageUrl();
            String price = reason.price();
            String title = reason.offerTitle();
            String spec = reason.skuSpec();
            String origin = null;

            // 1) preferred: re-run image search and match the bound offer.
            if (image == null || price == null || title == null) {
                try {
                    ImageSearchProductVO hit = findOfferInSearch(shopName, itemId, offerId);
                    if (hit != null) {
                        if (image == null) image = StringUtils.trimToNull(hit.getImageUrl());
                        if (price == null) price = StringUtils.trimToNull(hit.getPrice());
                        if (title == null) title = StringUtils.trimToNull(hit.getTitle());
                        origin = "SEARCH";
                    }
                } catch (Exception e) {
                    log.warn("Backfill search failed shopName={} itemId={} offerId={}: {}",
                            shopName, itemId, offerId, e.getMessage());
                }
            }

            // 2) fallback: derive from offer detail.
            if (image == null || price == null || spec == null) {
                try {
                    OfferDetailVO detail = crossborder1688ProductClient.queryProductDetail(offerId, COUNTRY);
                    if (image == null) image = bestDetailImage(detail);
                    if (price == null) price = bestDetailPrice(detail);
                    if (spec == null) spec = bestSkuSpec(detail, skuId);
                    if (image != null || price != null || spec != null) {
                        origin = origin == null ? "DETAIL" : origin + "+DETAIL";
                    }
                } catch (Exception e) {
                    log.warn("Backfill offer-detail failed shopName={} offerId={}: {}",
                            shopName, offerId, e.getMessage());
                }
            }

            if (image == null && price == null && title == null && spec == null) {
                result.unresolved++;
                continue;
            }

            String updated = ImageMatchReason.encode(reason.imageSource(), reason.querySource(),
                    reason.appliedQuery(), reason.detailUrl(), image, price, title, spec);
            shopProductMatchCandidateRepository.updateMatchReason(candidate.getId(), updated);
            result.backfilled++;
            if (origin != null && origin.startsWith("SEARCH")) {
                result.fromSearch++;
            } else {
                result.fromDetail++;
            }
            log.info("Backfill snapshot shopName={} itemId={} offerId={} origin={} img={} price={}",
                    shopName, itemId, offerId, origin, image != null, price);
        }
        log.info("Backfill snapshots done shopName={} result={}", shopName, result);
        return result;
    }

    private ImageSearchProductVO findOfferInSearch(String shopName, String itemId, String offerId) {
        if (StringUtils.isAnyBlank(itemId, offerId)) {
            return null;
        }
        ImageSearchResultVO res = imageSearchService.searchByShopProduct(shopName, itemId, SEARCH_LIMIT);
        if (res == null || res.getItems() == null) {
            return null;
        }
        for (ImageSearchProductVO item : res.getItems()) {
            if (offerId.equals(item.getProductId())) {
                return item;
            }
        }
        return null;
    }

    /** White image, else the first per-SKU image found in the spec matrix. */
    private static String bestDetailImage(OfferDetailVO detail) {
        if (detail == null) {
            return null;
        }
        if (StringUtils.isNotBlank(detail.getWhiteImageUrl())) {
            return detail.getWhiteImageUrl();
        }
        for (OfferSkuVO sku : nullSafe(detail.getSkus())) {
            for (OfferSkuAttributeVO attr : nullSafe(sku.getSkuAttributes())) {
                if (StringUtils.isNotBlank(attr.getSkuImageUrl())) {
                    return attr.getSkuImageUrl();
                }
            }
        }
        return null;
    }

    /** Minimum wholesale price across the SKU matrix, as a plain string; null when none is parseable. */
    private static String bestDetailPrice(OfferDetailVO detail) {
        if (detail == null) {
            return null;
        }
        BigDecimal min = null;
        for (OfferSkuVO sku : nullSafe(detail.getSkus())) {
            BigDecimal p = parse(sku.getPrice());
            if (p != null && (min == null || p.compareTo(min) < 0)) {
                min = p;
            }
        }
        return min == null ? null : min.stripTrailingZeros().toPlainString();
    }

    private static String bestSkuSpec(OfferDetailVO detail, String skuId) {
        if (detail == null || detail.getSkus() == null || detail.getSkus().isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(skuId)) {
            for (OfferSkuVO sku : detail.getSkus()) {
                if (sku == null || StringUtils.isBlank(sku.getSkuId())) {
                    continue;
                }
                if (skuId.trim().equals(sku.getSkuId().trim())
                        || skuId.trim().equals(String.valueOf(sku.getSkuId()).trim())) {
                    String label = SkuMatcher.specLabel(sku);
                    return StringUtils.isNotBlank(label) ? label.trim() : null;
                }
            }
        }
        if (detail.getSkus().size() == 1) {
            String label = SkuMatcher.specLabel(detail.getSkus().get(0));
            return StringUtils.isNotBlank(label) ? label.trim() : null;
        }
        return null;
    }

    private static BigDecimal parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Summary of a backfill run; all counts are per-binding. */
    @Data
    @Accessors(chain = true)
    public static class BackfillResult {
        private int total;
        private int alreadyOk;
        private int backfilled;
        private int fromSearch;
        private int fromDetail;
        private int unresolved;
        private int skipped;
    }
}
