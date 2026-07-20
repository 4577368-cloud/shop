package com.tang.plugin.service.publish.component.shopify;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Shopify product update component — GraphQL only.
 * Product-level fields via {@code productUpdate}; default variant price via {@code productVariantsBulkUpdate}.
 */
@Slf4j
@Component
public class ShopifyProductUpdateComponent {

    private static final String UPDATE_PRODUCT = """
            mutation UpdateProduct($product: ProductUpdateInput!) {
              productUpdate(product: $product) {
                product { id title status descriptionHtml }
                userErrors { field message }
              }
            }
            """;

    private static final String UPDATE_VARIANT_PRICE = """
            mutation UpdateVariantPrice($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                productVariants { id price }
                userErrors { field message }
              }
            }
            """;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    public void updateProductFields(String shopName, String shopDomain, String accessToken,
                                    String productGid, String title, String descriptionHtml, String status) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, productGid)) {
            throw new CustomException("Shopify update product missing credentials/id, shopName=" + shopName);
        }
        JSONObject product = new JSONObject();
        product.put("id", productGid);
        if (title != null) {
            product.put("title", title);
        }
        if (descriptionHtml != null) {
            product.put("descriptionHtml", descriptionHtml);
        }
        if (StringUtils.isNotBlank(status)) {
            product.put("status", status.trim().toUpperCase(Locale.ROOT));
        }
        JSONObject variables = new JSONObject();
        variables.put("product", product);

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, UPDATE_PRODUCT, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productUpdate");
        if (payload == null) {
            throw new CustomException("Shopify productUpdate payload null, shopName=" + shopName);
        }
        assertNoUserErrors(shopName, "productUpdate", payload.getJSONArray("userErrors"));
        if (payload.getJSONObject("product") == null) {
            throw new CustomException("Shopify productUpdate product null, shopName=" + shopName);
        }
        log.info("Shopify productUpdate ok shopName={} productId={}", shopName, productGid);
    }

    public void updateVariantPrice(String shopName, String shopDomain, String accessToken,
                                   String productGid, String variantGid, BigDecimal price) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, productGid, variantGid)) {
            throw new CustomException("Shopify update variant missing credentials/ids, shopName=" + shopName);
        }
        if (price == null || price.signum() < 0) {
            throw new CustomException("Shopify update variant price invalid, shopName=" + shopName);
        }
        JSONObject variant = new JSONObject();
        variant.put("id", variantGid);
        variant.put("price", price.toPlainString());

        JSONObject variables = new JSONObject();
        variables.put("productId", productGid);
        variables.put("variants", JSONArray.of(variant));

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, UPDATE_VARIANT_PRICE, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productVariantsBulkUpdate");
        if (payload == null) {
            throw new CustomException("Shopify productVariantsBulkUpdate payload null, shopName=" + shopName);
        }
        assertNoUserErrors(shopName, "productVariantsBulkUpdate", payload.getJSONArray("userErrors"));
        log.info("Shopify variant price update ok shopName={} productId={} variantId={}",
                shopName, productGid, variantGid);
    }

    private static void assertNoUserErrors(String shopName, String op, JSONArray userErrors) {
        if (CollectionUtils.isEmpty(userErrors)) {
            return;
        }
        String joined = userErrors.stream()
                .map(o -> {
                    JSONObject e = (JSONObject) o;
                    return e.getString("field") + ":" + e.getString("message");
                })
                .collect(Collectors.joining("; "));
        throw new CustomException("Shopify " + op + " userErrors, shopName=" + shopName + ", errors=" + joined);
    }
}
