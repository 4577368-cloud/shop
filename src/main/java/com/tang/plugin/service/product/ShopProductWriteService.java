package com.tang.plugin.service.product;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.product.ShopifyProductMirror;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.dto.product.ShopProductDetailVO;
import com.tang.plugin.domain.dto.product.ShopProductUpdateRequest;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 2/3: write editable fields back to Shopify, then refresh the local mirror from GraphQL.
 * Phase 3: optional optimistic concurrency via {@code expectedUpdatedAt}.
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

        // Ensure the product exists; Phase 3 conflict check against mirror updatedAt.
        ShopProductDetailVO current = shopProductQueryService.getDetail(shopName, itemId);
        assertNoConflict(req, current);

        shopifyProductUpdateComponent.updateProductFields(
                shopName, shop.getShopDomain(), shop.getAccessToken(),
                itemId, title, req.getDescription(), status);

        if (req.getDefaultVariantPrice() != null) {
            Optional<ThirdPlatformSku> first = thirdPlatformSkuRepository.findFirstByItem(shopName, itemId);
            String variantGid = first.map(ThirdPlatformSku::getThirdPlatformSkuId).orElse(null);
            if (StringUtils.isBlank(variantGid)) {
                throw new CustomException(
                        "no variant mirrored for price update; sync products first, shopName=" + shopName);
            }
            BigDecimal price = req.getDefaultVariantPrice();
            shopifyProductUpdateComponent.updateVariantPrice(
                    shopName, shop.getShopDomain(), shop.getAccessToken(),
                    itemId, variantGid, price);
        }

        refreshLocalMirror(shopName, shop, itemId);
        return shopProductQueryService.getDetail(shopName, itemId);
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
        // Truncate to millis: JSON Instant round-trips may drop nanos.
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
