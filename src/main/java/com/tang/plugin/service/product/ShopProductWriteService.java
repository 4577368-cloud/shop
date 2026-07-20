package com.tang.plugin.service.product;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.product.ShopifyProductMirror;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.dto.product.ShopProductDetailVO;
import com.tang.plugin.domain.dto.product.ShopProductUpdateRequest;
import com.tang.plugin.domain.dto.product.ShopProductVariantUpdate;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import com.tang.plugin.service.publish.component.ExchangeRateComponent;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductComponent;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductUpdateComponent;
import com.tang.plugin.service.publish.convert.ShopifyProductRequestAdapter;
import com.tang.plugin.service.shop.ShopifyEnabledShopProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 2–4: write editable fields back to Shopify, then refresh the local mirror from GraphQL.
 * Phase 3: optional optimistic concurrency via {@code expectedUpdatedAt}.
 * Phase 4: multi-variant price + inventory write-back.
 */
@Slf4j
@Service
public class ShopProductWriteService {

    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "DRAFT", "ARCHIVED");
    private static final String DEFAULT_CURRENCY = "USD";
    public static final String CODE_PRODUCT_CONFLICT = "PRODUCT_CONFLICT";

    @Resource
    private ShopifyEnabledShopProvider shopifyEnabledShopProvider;
    @Resource
    private ShopifyProductUpdateComponent shopifyProductUpdateComponent;
    @Resource
    private ShopifyProductComponent shopifyProductComponent;
    @Resource
    private ShopifyProductRequestAdapter shopifyProductRequestAdapter;
    @Resource
    private ExchangeRateComponent exchangeRateComponent;
    @Resource
    private ProductSyncService productSyncService;
    @Resource
    private ShopProductQueryService shopProductQueryService;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;

    public ShopProductDetailVO update(String shopName, ShopProductUpdateRequest req) {
        if (StringUtils.isBlank(shopName) || req == null || StringUtils.isBlank(req.getItemId())) {
            throw new CustomException("product update requires shopName and itemId");
        }
        String itemId = req.getItemId().trim();
        String title = req.getTitle() == null ? null : req.getTitle().trim();
        if (title != null && title.isEmpty()) {
            throw new CustomException("product title cannot be blank");
        }
        String status = StringUtils.trimToNull(req.getStatus());
        if (status != null) {
            status = status.toUpperCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(status)) {
                throw new CustomException("product status must be ACTIVE, DRAFT, or ARCHIVED");
            }
        }

        ShopifyEnabledShop shop = shopifyEnabledShopProvider.findByShopName(shopName)
                .orElseThrow(() -> new CustomException("shop not authorized, shopName=" + shopName));

        ShopProductDetailVO current = shopProductQueryService.getDetail(shopName, itemId);
        assertNoConflict(req, current);

        Set<String> mirroredVariantIds = thirdPlatformSkuRepository.listByItem(shopName, itemId).stream()
                .map(ThirdPlatformSku::getThirdPlatformSkuId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        shopifyProductUpdateComponent.updateProductFields(
                shopName, shop.getShopDomain(), shop.getAccessToken(),
                itemId, title, req.getDescription(), status);

        Map<String, BigDecimal> prices = collectPriceUpdates(req, itemId, shopName, mirroredVariantIds);
        if (!prices.isEmpty()) {
            shopifyProductUpdateComponent.updateVariantPrices(
                    shopName, shop.getShopDomain(), shop.getAccessToken(), itemId, prices);
        }

        Map<String, Integer> inventories = collectInventoryUpdates(req, mirroredVariantIds);
        if (!inventories.isEmpty()) {
            shopifyProductUpdateComponent.setVariantInventories(
                    shopName, shop.getShopDomain(), shop.getAccessToken(), itemId, inventories);
        }

        refreshLocalMirror(shopName, shop, itemId);
        return shopProductQueryService.getDetail(shopName, itemId);
    }

    private Map<String, BigDecimal> collectPriceUpdates(ShopProductUpdateRequest req, String itemId,
                                                        String shopName, Set<String> mirroredVariantIds) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        List<ShopProductVariantUpdate> variants = req.getVariants();
        if (variants != null) {
            for (ShopProductVariantUpdate v : variants) {
                if (v == null || v.getPrice() == null) {
                    continue;
                }
                String gid = StringUtils.trimToNull(v.getThirdPlatformSkuId());
                if (gid == null) {
                    throw new CustomException("variant update requires thirdPlatformSkuId");
                }
                if (!mirroredVariantIds.contains(gid)) {
                    throw new CustomException(
                            "variant not in mirror, shopName=" + shopName + ", variantId=" + gid);
                }
                if (v.getPrice().signum() < 0) {
                    throw new CustomException("variant price must be >= 0, variantId=" + gid);
                }
                prices.put(gid, v.getPrice());
            }
        }
        // Back-compat: defaultVariantPrice fills first variant when not already in variants[].
        if (req.getDefaultVariantPrice() != null) {
            Optional<ThirdPlatformSku> first = thirdPlatformSkuRepository.findFirstByItem(shopName, itemId);
            String variantGid = first.map(ThirdPlatformSku::getThirdPlatformSkuId).orElse(null);
            if (StringUtils.isBlank(variantGid)) {
                throw new CustomException(
                        "no variant mirrored for price update; sync products first, shopName=" + shopName);
            }
            prices.putIfAbsent(variantGid, req.getDefaultVariantPrice());
        }
        return prices;
    }

    private Map<String, Integer> collectInventoryUpdates(ShopProductUpdateRequest req,
                                                         Set<String> mirroredVariantIds) {
        Map<String, Integer> inventories = new LinkedHashMap<>();
        List<ShopProductVariantUpdate> variants = req.getVariants();
        if (variants == null) {
            return inventories;
        }
        for (ShopProductVariantUpdate v : variants) {
            if (v == null || v.getInventoryQuantity() == null) {
                continue;
            }
            String gid = StringUtils.trimToNull(v.getThirdPlatformSkuId());
            if (gid == null) {
                throw new CustomException("variant update requires thirdPlatformSkuId");
            }
            if (!mirroredVariantIds.contains(gid)) {
                throw new CustomException("variant not in mirror for inventory, variantId=" + gid);
            }
            if (v.getInventoryQuantity() < 0) {
                throw new CustomException("inventory quantity must be >= 0, variantId=" + gid);
            }
            inventories.put(gid, v.getInventoryQuantity());
        }
        return inventories;
    }

    private void assertNoConflict(ShopProductUpdateRequest req, ShopProductDetailVO current) {
        if (Boolean.TRUE.equals(req.getForce()) || req.getExpectedUpdatedAt() == null) {
            return;
        }
        Instant actual = current.getUpdatedAt();
        Instant expected = req.getExpectedUpdatedAt();
        if (actual == null) {
            return;
        }
        Instant a = actual.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant e = expected.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (!a.equals(e)) {
            throw new CustomException(
                    "product was updated elsewhere (Shopify or webhook); reload or force overwrite",
                    409,
                    CODE_PRODUCT_CONFLICT);
        }
    }

    private void refreshLocalMirror(String shopName, ShopifyEnabledShop shop, String itemId) {
        JSONObject node = shopifyProductComponent.fetchProductById(
                shopName, shop.getShopDomain(), shop.getAccessToken(), itemId);
        if (node == null) {
            log.warn("Shopify product missing after update shopName={} itemId={}", shopName, itemId);
            return;
        }
        BigDecimal rate = exchangeRateComponent.getExchangeRate(DEFAULT_CURRENCY);
        ShopifyProductMirror mirror = shopifyProductRequestAdapter.convert(
                node, shopName, DEFAULT_CURRENCY, rate);
        if (mirror == null || mirror.getProduct() == null) {
            throw new CustomException("failed to convert product after update, itemId=" + itemId);
        }
        productSyncService.upsertOne(mirror.getProduct(), mirror.getSkuList());
    }
}
