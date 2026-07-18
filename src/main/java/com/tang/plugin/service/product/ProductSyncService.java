package com.tang.plugin.service.product;

import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.product.SyncThirdPartyPlatformProductDTO;
import com.tang.plugin.domain.dto.product.SyncThirdProductDTO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformProductMedia;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.repository.ThirdPlatformProductMediaRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.publish.handler.BasePublishProductHandler;
import com.tang.plugin.service.publish.handler.ProductPlatformHandlerHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Product sync facade: orchestrates handler pull + standardized mirror persistence.
 * Independent from order sync; SPU + SKU + media persist inside one transaction per product.
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
    private TxManger txManger;

    /**
     * Sync Shopify products for one shop with an optional incremental window.
     *
     * @param updatedAtMin lower bound (UTC); null pulls all products.
     */
    public void syncShopifyByShopName(String shopName, Instant updatedAtMin) {
        SyncThirdProductDTO changeDTO = new SyncThirdProductDTO()
                .setShopName(shopName)
                .setUpdatedAtMin(updatedAtMin);

        BasePublishProductHandler handler = productPlatformHandlerHolder.get(PluginType.SHOPIFY.getCode());
        SyncThirdPartyPlatformProductDTO result = handler.syncProducts(changeDTO);
        persist(shopName, result);
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
