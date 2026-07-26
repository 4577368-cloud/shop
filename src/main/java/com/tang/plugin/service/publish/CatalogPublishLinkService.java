package com.tang.plugin.service.publish;

import com.alibaba.fastjson2.JSONArray;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.domain.dto.publish.PublishVariantSnapshot;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import com.tang.plugin.service.catalog.TangbuyMallClient;
import com.tang.plugin.service.match.image.ImageMatchReason;
import com.tang.plugin.service.match.sku.ItemGetSkuMatrixParser;
import com.tang.plugin.service.match.sku.SkuMatcher;
import com.tang.plugin.service.match.sku.SkuMatcher.VariantAlignment;
import com.tang.plugin.service.skualign.SkuAlignV1Service;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Establishes the 1:1 source binding for products published from the Tangbuy catalog (route B).
 *
 * <p>A published product already <em>is</em> its source — there is nothing to image-match. So on publish
 * (and via a one-shot backfill for products published before publish-time linking existed) we write a
 * definitive {@code CATALOG} candidate + ACTIVE {@link ShopProductBinding} per Shopify variant that was
 * created from the Tangbuy SKU matrix.
 *
 * <p>Idempotent: if a variant already has a live binding, linking is skipped (never clobbers a
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
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private TangbuyMallClient tangbuyMallClient;
    @Resource
    private TxManger txManger;
    @Resource
    private SkuAlignV1Service skuAlignV1Service;

    public record VariantPublishLink(String shopifyVariantGid, String tangbuySkuId) {
    }

    /**
     * Link a freshly published catalog product to its source (single default variant).
     */
    public boolean linkPublished(String shopName, TangbuyCatalogProduct candidate,
                                 String shopifyProductGid, String shopifyVariantGid) {
        if (candidate == null) {
            return false;
        }
        LinkOutcome outcome = link(shopName, shopifyProductGid, shopifyVariantGid,
                firstNonBlank(candidate.getOfferId1688(), candidate.getTangbuyProductId(), candidate.getCandidateId()),
                firstNonBlank(candidate.getSkuId(), candidate.getOfferId1688(), candidate.getCandidateId()),
                candidate.getImageUrl(),
                candidate.getPrice(),
                firstNonBlank(candidate.getTangbuyUrl(), candidate.getUrl1688()));
        finishCatalogPublishSeed(shopName, shopifyProductGid);
        return outcome == LinkOutcome.LINKED || outcome == LinkOutcome.REPLACED;
    }

    /**
     * Link every Shopify variant created at publish time to its Tangbuy SKU (same order as publish snapshots).
     */
    public void linkPublishedVariants(String shopName,
                                      TangbuyCatalogProduct candidate,
                                      String shopifyProductGid,
                                      List<VariantPublishLink> links) {
        if (candidate == null || links == null || links.isEmpty()) {
            return;
        }
        String offerId = firstNonBlank(candidate.getOfferId1688(), candidate.getTangbuyProductId(),
                candidate.getCandidateId());
        String detailUrl = firstNonBlank(candidate.getTangbuyUrl(), candidate.getUrl1688());
        String image = candidate.getImageUrl();
        BigDecimal price = candidate.getPrice();
        for (VariantPublishLink row : links) {
            if (row == null || StringUtils.isAnyBlank(row.shopifyVariantGid(), row.tangbuySkuId())) {
                continue;
            }
            link(shopName, shopifyProductGid, row.shopifyVariantGid(), offerId, row.tangbuySkuId().trim(),
                    image, price, detailUrl);
        }
        finishCatalogPublishSeed(shopName, shopifyProductGid);
    }

    public static List<VariantPublishLink> zipVariantLinks(List<String> shopifyVariantGids,
                                                         List<PublishVariantSnapshot> snapshots) {
        List<VariantPublishLink> out = new ArrayList<>();
        if (shopifyVariantGids == null || snapshots == null) {
            return out;
        }
        int n = Math.min(shopifyVariantGids.size(), snapshots.size());
        for (int i = 0; i < n; i++) {
            PublishVariantSnapshot snap = snapshots.get(i);
            String gid = shopifyVariantGids.get(i);
            if (snap == null || StringUtils.isBlank(gid)) {
                continue;
            }
            String skuId = StringUtils.trimToNull(snap.getSkuId());
            if (skuId == null) {
                continue;
            }
            out.add(new VariantPublishLink(gid.trim(), skuId));
        }
        return out;
    }

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
                LinkOutcome outcome = link(shopName, record.getShopifyProductId(), record.getShopifyVariantId(),
                        offerId, skuId, image, price, detailUrl);
                switch (outcome) {
                    case LINKED -> result.linked++;
                    case REPLACED -> result.replaced++;
                    case ALREADY -> result.alreadyLinked++;
                    case SKIPPED -> result.skipped++;
                }
                repairPublishedVariantBindings(shopName, record, catalog, image, price, detailUrl, offerId);
            } catch (Exception e) {
                result.failed++;
                log.warn("Backfill publish-binding failed shopName={} candidateId={}: {}",
                        shopName, record.getCandidateId(), e.getMessage());
            }
        }
        log.info("Backfill publish-bindings done shopName={} result={}", shopName, result);
        return result;
    }

    private void finishCatalogPublishSeed(String shopName, String productGid) {
        if (StringUtils.isAnyBlank(shopName, productGid)) {
            return;
        }
        try {
            skuAlignV1Service.seedCatalogPublishAlignments(shopName, productGid);
        } catch (Exception e) {
            log.warn("Catalog publish SKU seed failed shop={} product={}: {}",
                    shopName, productGid, e.getMessage());
        }
    }

    private void repairPublishedVariantBindings(String shopName,
                                                ProductPublishRecord record,
                                                TangbuyCatalogProduct catalog,
                                                String image,
                                                BigDecimal price,
                                                String detailUrl,
                                                String offerId) {
        String productId = StringUtils.trimToNull(record.getShopifyProductId());
        if (productId == null) {
            return;
        }
        List<ThirdPlatformSku> variants = thirdPlatformSkuRepository.listByItem(shopName, productId);
        if (variants.isEmpty()) {
            return;
        }
        long boundCount = variants.stream()
                .filter(v -> shopProductBindingRepository
                        .findBindableBySkuId(shopName, v.getThirdPlatformSkuId())
                        .isPresent())
                .count();
        if (boundCount >= variants.size()) {
            finishCatalogPublishSeed(shopName, productId);
            return;
        }
        if (StringUtils.isBlank(detailUrl) || !tangbuyMallClient.isConfigured()) {
            finishCatalogPublishSeed(shopName, productId);
            return;
        }
        JSONArray itemSkus;
        try {
            itemSkus = tangbuyMallClient.itemGetProductSkus(detailUrl);
        } catch (Exception e) {
            log.warn("Publish variant repair itemGet failed shop={} product={}: {}",
                    shopName, productId, e.getMessage());
            finishCatalogPublishSeed(shopName, productId);
            return;
        }
        var matrix = ItemGetSkuMatrixParser.parseOfferSkus(itemSkus);
        if (matrix.isEmpty()) {
            finishCatalogPublishSeed(shopName, productId);
            return;
        }
        List<VariantAlignment> alignments = SkuMatcher.align(variants, matrix);
        for (VariantAlignment a : alignments) {
            if (!a.matched() || StringUtils.isBlank(a.skuId())) {
                continue;
            }
            if (shopProductBindingRepository.findBindableBySkuId(shopName, a.variantGid()).isPresent()) {
                continue;
            }
            link(shopName, productId, a.variantGid(), offerId, a.skuId(), image, price, detailUrl);
        }
        finishCatalogPublishSeed(shopName, productId);
        log.info("Publish variant repair shop={} product={} variants={} boundBefore={}",
                shopName, productId, variants.size(), boundCount);
    }

    private LinkOutcome link(String shopName, String productGid, String variantGid, String tangbuyProductId,
                             String tangbuySkuId, String imageUrl, BigDecimal price, String detailUrl) {
        if (StringUtils.isAnyBlank(shopName, variantGid, tangbuyProductId, tangbuySkuId)) {
            return LinkOutcome.SKIPPED;
        }
        var existing = shopProductBindingRepository.findBindableBySkuId(shopName, variantGid);
        if (existing.isPresent()) {
            return LinkOutcome.ALREADY;
        }
        String priceStr = price == null ? null : price.stripTrailingZeros().toPlainString();
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
            log.info("Catalog publish LINK shopName={} productGid={} variantGid={} offerId={} skuId={} candidateId={}",
                    shopName, productGid, variantGid, tangbuyProductId, tangbuySkuId, candidateId);
        });
        return LinkOutcome.LINKED;
    }

    private enum LinkOutcome { LINKED, REPLACED, ALREADY, SKIPPED }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    @Data
    @Accessors(chain = true)
    public static class BackfillResult {
        private int total;
        private int linked;
        private int replaced;
        private int alreadyLinked;
        private int skipped;
        private int failed;
    }
}
