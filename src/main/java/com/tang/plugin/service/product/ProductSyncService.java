package com.tang.plugin.service.product;

import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.product.SyncThirdPartyPlatformProductDTO;
import com.tang.plugin.domain.dto.product.SyncThirdProductDTO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformProductMedia;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ThirdPlatformProductMediaRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.publish.handler.BasePublishProductHandler;
import com.tang.plugin.service.publish.handler.ProductPlatformHandlerHolder;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import com.tang.plugin.service.webhook.component.ShopifyWebhookComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Product sync facade: orchestrates handler pull + standardized mirror persistence.
 * Independent from order sync; SPU + SKU + media persist inside one transaction per product.
 * Full sync also reconciles deletions and refreshes webhook subscriptions (incl. products/delete).
 */
@Slf4j
@Service
public class ProductSyncService {

    @Resource
    private ProductPlatformHandlerHolder productPlatformHandlerHolder;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private ThirdPlatformProductMediaRepository thirdPlatformProductMediaRepository;
    @Resource
    private ProductMirrorDeleteService productMirrorDeleteService;
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyWebhookComponent shopifyWebhookComponent;
    @Resource
    private TxManger txManger;

    /**
     * Fire-and-forget full product pull triggered right after a successful OAuth callback.
     * Runs off the request thread so it never blocks the redirect back to the frontend; any
     * failure is logged and swallowed (the shop is still authorized, sync can be retried later).
     */
    @Async
    public void asyncFullSyncShopify(String shopName) {
        try {
            log.info("Async product sync (post-auth) started shopName={}", shopName);
            syncShopifyByShopName(shopName, null);
            log.info("Async product sync (post-auth) finished shopName={}", shopName);
        } catch (Exception e) {
            log.error("Async product sync (post-auth) failed shopName={}", shopName, e);
        }
    }

    /**
     * Sync Shopify products for one shop with an optional incremental window.
     *
     * @param updatedAtMin lower bound (UTC); null pulls all products and reconciles deletions.
     */
    public void syncShopifyByShopName(String shopName, Instant updatedAtMin) {
        boolean fullSync = updatedAtMin == null;
        SyncThirdProductDTO changeDTO = new SyncThirdProductDTO()
                .setShopName(shopName)
                .setUpdatedAtMin(updatedAtMin);

        BasePublishProductHandler handler = productPlatformHandlerHolder.get(PluginType.SHOPIFY.getCode());
        SyncThirdPartyPlatformProductDTO result = handler.syncProducts(changeDTO);
        persist(shopName, result);

        if (fullSync) {
            reconcileDeleted(shopName, result);
            shopProductBindingRepository.deactivateOrphansForShop(shopName);
            ensureWebhooksRegistered(shopName);
        }
    }

    /**
     * Upsert one mirrored product (SPU + SKUs + media). Used by product webhooks and tests.
     */
    public void upsertOne(ThirdPlatformProduct product, List<ThirdPlatformSku> skus) {
        if (product == null || StringUtils.isBlank(product.getThirdPlatformItemId())) {
            return;
        }
        persistOne(product, skus == null ? List.of() : skus);
    }

    private void persist(String shopName, SyncThirdPartyPlatformProductDTO result) {
        if (result == null || CollectionUtils.isEmpty(result.getThirdPlatformProductList())) {
            log.info("Product sync nothing to persist shopName={}", shopName);
            return;
        }
        Map<String, List<ThirdPlatformSku>> skusByItem = CollectionUtils.isEmpty(result.getThirdPlatformSkuList())
                ? Map.of()
                : result.getThirdPlatformSkuList().stream()
                        .collect(Collectors.groupingBy(ThirdPlatformSku::getThirdPlatformItemId));

        int ok = 0;
        int fail = 0;
        for (ThirdPlatformProduct product : result.getThirdPlatformProductList()) {
            String productId = product.getThirdPlatformItemId();
            List<ThirdPlatformSku> skus = skusByItem.getOrDefault(productId, List.of());
            try {
                persistOne(product, skus);
                ok++;
                log.info("Product mirror persisted shopName={} productId={} skus={} media={}",
                        shopName, productId, skus.size(), product.getMediaList().size());
            } catch (Exception e) {
                fail++;
                log.error("Product mirror persist failed shopName={} productId={}", shopName, productId, e);
            }
        }
        log.info("Product sync persist done shopName={} success={} fail={}", shopName, ok, fail);
    }

    /**
     * Soft-delete local SPU mirrors that Shopify no longer returns. Skipped when the pull was
     * truncated by max-pages (would otherwise wipe products beyond the page cap).
     */
    private void reconcileDeleted(String shopName, SyncThirdPartyPlatformProductDTO result) {
        if (result == null) {
            log.warn("Product sync reconcile skipped (null result) shopName={}", shopName);
            return;
        }
        if (result.isCatalogTruncated()) {
            log.warn("Product sync reconcile skipped (catalog truncated) shopName={}", shopName);
            return;
        }
        List<ThirdPlatformProduct> remote = result.getThirdPlatformProductList();
        Set<String> remoteIds = new HashSet<>();
        if (CollectionUtils.isNotEmpty(remote)) {
            for (ThirdPlatformProduct p : remote) {
                if (p != null && StringUtils.isNotBlank(p.getThirdPlatformItemId())) {
                    remoteIds.add(p.getThirdPlatformItemId());
                }
            }
        }
        List<String> localIds = thirdPlatformProductRepository.listActiveItemIds(shopName);
        int removed = 0;
        for (String localId : localIds) {
            if (!remoteIds.contains(localId)) {
                if (productMirrorDeleteService.softDeleteCascade(shopName, localId)) {
                    removed++;
                }
            }
        }
        log.info("Product sync reconcile done shopName={} remote={} local={} softDeleted={}",
                shopName, remoteIds.size(), localIds.size(), removed);
    }

    /** Idempotent: registers any missing topics (e.g. products/delete) for already-authorized shops. */
    private void ensureWebhooksRegistered(String shopName) {
        try {
            ShopifyStoreAuth auth = shopifyStoreAuthService.findActiveByShopName(shopName).orElse(null);
            if (auth == null || StringUtils.isAnyBlank(auth.getShopDomain(), auth.getAccessToken())) {
                return;
            }
            shopifyWebhookComponent.registerDefaultWebhooks(
                    auth.getShopName(), auth.getShopDomain(), auth.getAccessToken());
        } catch (Exception e) {
            log.warn("Ensure Shopify webhooks after product sync failed shopName={}: {}",
                    shopName, e.getMessage());
        }
    }

    private void persistOne(ThirdPlatformProduct product, List<ThirdPlatformSku> skus) {
        String shopName = product.getShopName();
        String productId = product.getThirdPlatformItemId();
        txManger.run(() -> {
            thirdPlatformProductRepository.upsert(product);

            thirdPlatformSkuRepository.softDeleteByItem(shopName, productId);
            for (ThirdPlatformSku sku : skus) {
                thirdPlatformSkuRepository.upsert(sku);
            }

            thirdPlatformProductMediaRepository.softDeleteByItem(shopName, productId);
            for (ThirdPlatformProductMedia media : product.getMediaList()) {
                thirdPlatformProductMediaRepository.upsert(media);
            }
        });
    }
}
