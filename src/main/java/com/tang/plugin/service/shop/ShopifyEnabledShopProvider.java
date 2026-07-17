package com.tang.plugin.service.shop;

import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enabled Shopify shops: JDBC ACTIVE auth first; yml test-shops as local-dev fallback only.
 */
@Slf4j
@Component
public class ShopifyEnabledShopProvider {

    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyProperties shopifyProperties;

    public List<ShopifyEnabledShop> listEnabled() {
        Map<String, ShopifyEnabledShop> byDomain = new LinkedHashMap<>();
        for (ShopifyStoreAuth auth : shopifyStoreAuthService.listActive()) {
            byDomain.put(auth.getShopDomain().toLowerCase(), toEnabled(auth));
        }
        for (ShopifyEnabledShop fallback : listTestShopFallback()) {
            byDomain.putIfAbsent(fallback.getShopDomain().toLowerCase(), fallback);
        }
        return new ArrayList<>(byDomain.values());
    }

    public Optional<ShopifyEnabledShop> findByShopName(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Optional.empty();
        }
        Optional<ShopifyEnabledShop> fromDb = shopifyStoreAuthService.findActiveByShopName(shopName)
                .map(this::toEnabled);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        return listTestShopFallback().stream()
                .filter(shop -> shopName.equals(shop.getShopName()))
                .findFirst();
    }

    public Optional<ShopifyEnabledShop> findByShopDomain(String shopDomain) {
        if (StringUtils.isBlank(shopDomain)) {
            return Optional.empty();
        }
        String normalized = shopDomain.trim().toLowerCase();
        Optional<ShopifyEnabledShop> fromDb = shopifyStoreAuthService.findActiveByShopDomain(normalized)
                .map(this::toEnabled);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        return listTestShopFallback().stream()
                .filter(shop -> normalized.equalsIgnoreCase(shop.getShopDomain()))
                .findFirst();
    }

    private List<ShopifyEnabledShop> listTestShopFallback() {
        List<ShopifyEnabledShop> result = new ArrayList<>();
        if (shopifyProperties.getTestShops() == null) {
            return result;
        }
        for (ShopifyProperties.TestShop testShop : shopifyProperties.getTestShops()) {
            if (testShop == null
                    || StringUtils.isAnyBlank(testShop.getShopName(), testShop.getShopDomain(), testShop.getAccessToken())) {
                continue;
            }
            result.add(new ShopifyEnabledShop()
                    .setShopName(testShop.getShopName().trim())
                    .setShopDomain(testShop.getShopDomain().trim().toLowerCase())
                    .setAccessToken(testShop.getAccessToken().trim()));
        }
        return result;
    }

    private ShopifyEnabledShop toEnabled(ShopifyStoreAuth auth) {
        return new ShopifyEnabledShop()
                .setShopName(auth.getShopName())
                .setShopDomain(auth.getShopDomain())
                .setAccessToken(auth.getAccessToken());
    }
}
