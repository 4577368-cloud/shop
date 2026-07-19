package com.tang.plugin.service.publish;

import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import com.tang.plugin.service.match.image.ImageMatchReason;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Establishes the 1:1 source binding for products published from the Tangbuy catalog (route B).
 *
 * <p>A published product already <em>is</em> its source — there is nothing to image-match. So on publish
 * (and via a one-shot backfill for products published before this existed) we write a definitive
 * {@code CATALOG} candidate + ACTIVE {@link ShopProductBinding} carrying the source image/price snapshot,
 * anchored on the created Shopify variant GID. This makes such products render as "已关联 · 来自 Tangbuy 商城"
 * and, crucially, excludes them from the image-search auto-matching that targets only unbound products.
 *
 * <p>Idempotent: if the variant already has a live binding, linking is skipped (never clobbers a
 * user/AI match). Fail-open at publish time: a link failure is logged and never fails the publish.
 */
@Slf4j
@Service
public class CatalogPublishLinkService {

    private static final String BIND_SOURCE_FROM_PUBLISH = "FROM_PUBLISH";

    @Resource
    private TangbuyCatalogService tangbuyCatalogService;
    @Resource
    private ProductPublishRecordRepository productPublishRecordRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private TxManger txManger;

    /**
     * Link a freshly published catalog product to its source. Returns true when a binding was written,
     * false when skipped (missing variant GID, or the variant already has a live binding).
     */
    public boolean linkPublished(String shopName, TangbuyCatalogProduct candidate,
                                 String shopifyProductGid, String shopifyVariantGid) {
        if (candidate == null) {
            return false;
        }
        return link(shopName, shopifyProductGid, shopifyVariantGid,
                firstNonBlank(candidate.getOfferId1688(), candidate.getTangbuyProductId(), candidate.getCandidateId()),
                firstNonBlank(candidate.getSkuId(), candidate.getOfferId1688(), candidate.getCandidateId()),
                candidate.getImageUrl(),
                candidate.getPrice(),
                firstNonBlank(candidate.getTangbuyUrl(), candidate.getUrl1688()));
    }

    /**
     * One-shot repair: for every PUBLISHED record of a shop whose Shopify variant has no live binding,
     * create the CATALOG binding from the catalog entry (image/price/urls), falling back to the record's
     * own snapshot fields when the catalog entry is gone. Idempotent and fail-open per record.
     */
    public BackfillResult backfillPublishedBindings(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return new BackfillResult();
        }
        BackfillResult result = new BackfillResult();
        for (ProductPublishRecord record : productPublishRecordRepository.listByShop(shopName)) {
            if (record.getPublishStatus() != ProductPublishStatus.PUBLISHED) {
                continue;
            }
            if (StringUtils.isBlank(record.getShopifyVariantId())) {
                result.skipped++;
                continue;
            }
            result.total++;
            try {
                TangbuyCatalogProduct catalog =
                        tangbuyCatalogService.findById(record.getCandidateId()).orElse(null);
                String image = catalog != null ? catalog.getImageUrl() : null;
                BigDecimal price = catalog != null ? catalog.getPrice() : record.getSourcePrice();
                String offerId = firstNonBlank(record.getOfferId1688(), record.getTangbuyProductId(),
                        record.getCandidateId());
                String skuId = firstNonBlank(record.getSkuId(), offerId);
                String detailUrl = catalog != null
                        ? firstNonBlank(catalog.getTangbuyUrl(), catalog.getUrl1688()) : null;
                boolean linked = link(shopName, record.getShopifyProductId(), record.getShopifyVariantId(),
                        offerId, skuId, image, price, detailUrl);
                if (linked) {
                    result.linked++;
                } else {
                    result.alreadyLinked++;
                }
            } catch (Exception e) {
                result.failed++;
                log.warn("Backfill publish-binding failed shopName={} candidateId={}: {}",
                        shopName, record.getCandidateId(), e.getMessage());
            }
        }
        log.info("Backfill publish-bindings done shopName={} result={}", shopName, result);
        return result;
    }

    private boolean link(String shopName, String productGid, String variantGid, String tangbuyProductId,
                         String tangbuySkuId, String imageUrl, BigDecimal price, String detailUrl) {
        if (StringUtils.isAnyBlank(shopName, variantGid, tangbuyProductId, tangbuySkuId)) {
            return false;
        }
        // Never clobber an existing live (ACTIVE/PENDING) binding on this variant.
        if (shopProductBindingRepository.findBindableBySkuId(shopName, variantGid).isPresent()) {
            return false;
        }
        String priceStr = price == null ? null : price.stripTrailingZeros().toPlainString();
        // imageSource/querySource intentionally empty: this is a 1:1 catalog link, not an image search.
        String matchReason = ImageMatchReason.encode(null, null, null, detailUrl, imageUrl, priceStr);

        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(shopName)
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(productGid)
                .setThirdPlatformSkuId(variantGid)
                .setTangbuyProductId(tangbuyProductId)
                .setTangbuySkuId(tangbuySkuId)
                .setMatchSource(MatchSource.CATALOG)
                .setMatchScore(BigDecimal.ZERO)
                .setMatchReason(matchReason)
                .setStatus(MatchStatus.CONFIRMED);

        txManger.run(() -> {
            Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);
            ShopProductBinding binding = new ShopProductBinding()
                    .setShopName(shopName)
                    .setShopType(PluginType.SHOPIFY.getCode())
                    .setThirdPlatformItemId(productGid)
                    .setThirdPlatformSkuId(variantGid)
                    .setTangbuyProductId(tangbuyProductId)
                    .setTangbuySkuId(tangbuySkuId)
                    .setBindSource(BIND_SOURCE_FROM_PUBLISH)
                    .setCandidateId(candidateId)
                    .setBindStatus(BindingStatus.ACTIVE);
            shopProductBindingRepository.upsertActive(binding);
            log.info("Catalog publish link shopName={} productGid={} variantGid={} offerId={} candidateId={}",
                    shopName, productGid, variantGid, tangbuyProductId, candidateId);
        });
        return true;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /** Summary of a publish-binding backfill run. */
    @Data
    @Accessors(chain = true)
    public static class BackfillResult {
        private int total;
        private int linked;
        private int alreadyLinked;
        private int skipped;
        private int failed;
    }
}
