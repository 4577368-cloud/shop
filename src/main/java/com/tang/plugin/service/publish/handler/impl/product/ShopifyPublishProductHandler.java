package com.tang.plugin.service.publish.handler.impl.product;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.product.PublicProductBO;
import com.tang.plugin.domain.bo.product.ShopifyProductMirror;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.dto.product.PublishProductResultDTO;
import com.tang.plugin.domain.dto.product.SyncThirdPartyPlatformProductDTO;
import com.tang.plugin.domain.dto.product.SyncThirdProductDTO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.service.publish.component.ExchangeRateComponent;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductComponent;
import com.tang.plugin.service.publish.convert.ShopifyProductRequestAdapter;
import com.tang.plugin.service.publish.handler.BasePublishProductHandler;
import com.tang.plugin.service.shop.ShopifyEnabledShopProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shopify product sync handler. Pulls products only in P1 (no publish/mutation).
 * Network calls delegate to {@link ShopifyProductComponent}; raw JSON stays in the adapter.
 */
@Slf4j
@Component
public class ShopifyPublishProductHandler extends BasePublishProductHandler {

    @Resource
    private ShopifyEnabledShopProvider shopifyEnabledShopProvider;
    @Resource
    private ShopifyProductComponent shopifyProductComponent;
    @Resource
    private ShopifyProductRequestAdapter shopifyProductRequestAdapter;
    @Resource
    private ExchangeRateComponent exchangeRateComponent;

    public ShopifyPublishProductHandler() {
        this.channelCode = PluginType.SHOPIFY.getCode();
    }

    @Override
    protected SyncThirdPartyPlatformProductDTO getThirdPartyPlatformProductList(SyncThirdProductDTO changeDTO) {
        String shopName = changeDTO.getShopName();
        ShopifyEnabledShop shop = shopifyEnabledShopProvider.findByShopName(shopName)
                .orElseThrow(() -> new CustomException("Shopify shop not enabled, shopName=" + shopName));

        String currency = changeDTO.getCurrency() != null ? changeDTO.getCurrency() : getShopCurrency(shopName);
        BigDecimal exchangeRate = changeDTO.getExchangeRate() != null
                ? changeDTO.getExchangeRate()
                : exchangeRateComponent.getExchangeRate(currency);

        List<JSONObject> nodes;
        boolean truncated;
        var fetch = shopifyProductComponent.fetchProducts(
                shopName, shop.getShopDomain(), shop.getAccessToken(), changeDTO.getUpdatedAtMin());
        nodes = fetch.products();
        truncated = fetch.truncated();

        List<ThirdPlatformProduct> productList = new ArrayList<>();
        List<ThirdPlatformSku> skuList = new ArrayList<>();
        for (JSONObject node : nodes) {
            ShopifyProductMirror mirror = shopifyProductRequestAdapter.convert(node, shopName, currency, exchangeRate);
            if (mirror == null || mirror.getProduct() == null) {
                continue;
            }
            productList.add(mirror.getProduct());
            skuList.addAll(mirror.getSkuList());
        }

        log.info("Shopify product sync converted shopName={} products={} skus={} truncated={}",
                shopName, productList.size(), skuList.size(), truncated);
        return SyncThirdPartyPlatformProductDTO.builder()
                .thirdPlatformProductList(productList)
                .thirdPlatformSkuList(skuList)
                .catalogTruncated(truncated)
                .build();
    }

    @Override
    protected void publishProduct(PublicProductBO productBO, PublishProductResultDTO publishProductResultDTO) {
        log.warn("Shopify publishProduct is a no-op in P1 shopName={}", Optional.ofNullable(productBO)
                .map(PublicProductBO::getShopName).orElse(null));
        publishProductResultDTO.setSuccess(false);
        publishProductResultDTO.setMessage("Shopify product publish not supported in P1");
    }
}
