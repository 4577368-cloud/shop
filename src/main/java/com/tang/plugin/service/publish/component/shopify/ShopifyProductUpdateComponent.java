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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shopify product update component — GraphQL only.
 * Product fields via {@code productUpdate}; variant prices via {@code productVariantsBulkUpdate};
 * inventory via {@code inventorySetQuantities} (Phase 4).
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

    private static final String UPDATE_VARIANT_PRICES = """
            mutation UpdateVariantPrices($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                productVariants { id price }
                userErrors { field message }
              }
            }
            """;

    private static final String VARIANT_INVENTORY_ITEMS = """
            query VariantInventoryItems($id: ID!, $first: Int!) {
              product(id: $id) {
                variants(first: $first) {
                  nodes {
                    id
                    inventoryItem { id tracked }
                  }
                }
              }
            }
            """;

    private static final String PRIMARY_LOCATION = """
            query PrimaryLocation {
              locations(first: 1, includeInactive: false) {
                nodes { id name }
              }
            }
            """;

    private static final String SET_INVENTORY = """
            mutation SetInventory($input: InventorySetQuantitiesInput!) {
              inventorySetQuantities(input: $input) {
                userErrors { field message }
              }
            }
            """;

    private static final String TRACK_INVENTORY_ITEM = """
            mutation TrackInventoryItem($id: ID!, $input: InventoryItemInput!) {
              inventoryItemUpdate(id: $id, input: $input) {
                inventoryItem { id tracked }
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
        boolean any = title != null || descriptionHtml != null || StringUtils.isNotBlank(status);
        if (!any) {
            return;
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

    /** Back-compat single-variant price update. */
    public void updateVariantPrice(String shopName, String shopDomain, String accessToken,
                                   String productGid, String variantGid, BigDecimal price) {
        Map<String, BigDecimal> one = new LinkedHashMap<>();
        one.put(variantGid, price);
        updateVariantPrices(shopName, shopDomain, accessToken, productGid, one);
    }

    /**
     * Bulk-update variant prices. {@code pricesByVariantGid} must be non-empty; values must be ≥ 0.
     */
    public void updateVariantPrices(String shopName, String shopDomain, String accessToken,
                                    String productGid, Map<String, BigDecimal> pricesByVariantGid) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, productGid)) {
            throw new CustomException("Shopify update variants missing credentials/ids, shopName=" + shopName);
        }
        if (pricesByVariantGid == null || pricesByVariantGid.isEmpty()) {
            return;
        }
        JSONArray variants = new JSONArray();
        for (Map.Entry<String, BigDecimal> e : pricesByVariantGid.entrySet()) {
            if (StringUtils.isBlank(e.getKey()) || e.getValue() == null || e.getValue().signum() < 0) {
                throw new CustomException("Shopify update variant price invalid, shopName=" + shopName
                        + ", variantId=" + e.getKey());
            }
            JSONObject variant = new JSONObject();
            variant.put("id", e.getKey());
            variant.put("price", e.getValue().toPlainString());
            variants.add(variant);
        }

        JSONObject variables = new JSONObject();
        variables.put("productId", productGid);
        variables.put("variants", variants);

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, UPDATE_VARIANT_PRICES, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productVariantsBulkUpdate");
        if (payload == null) {
            throw new CustomException("Shopify productVariantsBulkUpdate payload null, shopName=" + shopName);
        }
        assertNoUserErrors(shopName, "productVariantsBulkUpdate", payload.getJSONArray("userErrors"));
        log.info("Shopify variant prices update ok shopName={} productId={} count={}",
                shopName, productGid, variants.size());
    }

    /**
     * Set available inventory at the shop's first active location for the given variant GIDs.
     * Ensures inventory items are tracked before writing quantities.
     */
    public void setVariantInventories(String shopName, String shopDomain, String accessToken,
                                      String productGid, Map<String, Integer> qtyByVariantGid) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, productGid)) {
            throw new CustomException("Shopify set inventory missing credentials/ids, shopName=" + shopName);
        }
        if (qtyByVariantGid == null || qtyByVariantGid.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> e : qtyByVariantGid.entrySet()) {
            if (StringUtils.isBlank(e.getKey()) || e.getValue() == null || e.getValue() < 0) {
                throw new CustomException("Shopify inventory quantity invalid, shopName=" + shopName
                        + ", variantId=" + e.getKey());
            }
        }

        Map<String, String> inventoryItemByVariant = fetchInventoryItemIds(
                shopName, shopDomain, accessToken, productGid);
        String locationId = fetchPrimaryLocationId(shopName, shopDomain, accessToken);

        List<JSONObject> quantities = new ArrayList<>();
        for (Map.Entry<String, Integer> e : qtyByVariantGid.entrySet()) {
            String variantGid = e.getKey();
            String inventoryItemId = inventoryItemByVariant.get(variantGid);
            if (StringUtils.isBlank(inventoryItemId)) {
                throw new CustomException(
                        "Shopify inventoryItem missing for variant, shopName=" + shopName
                                + ", variantId=" + variantGid);
            }
            ensureInventoryTracked(shopName, shopDomain, accessToken, inventoryItemId);

            JSONObject q = new JSONObject();
            q.put("inventoryItemId", inventoryItemId);
            q.put("locationId", locationId);
            q.put("quantity", e.getValue());
            quantities.add(q);
        }

        JSONObject input = new JSONObject();
        input.put("name", "available");
        input.put("reason", "correction");
        input.put("ignoreCompareQuantity", true);
        input.put("quantities", quantities);

        JSONObject variables = new JSONObject();
        variables.put("input", input);

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, SET_INVENTORY, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("inventorySetQuantities");
        if (payload == null) {
            throw new CustomException("Shopify inventorySetQuantities payload null, shopName=" + shopName);
        }
        assertNoUserErrors(shopName, "inventorySetQuantities", payload.getJSONArray("userErrors"));
        log.info("Shopify inventory set ok shopName={} productId={} count={}",
                shopName, productGid, quantities.size());
    }

    private Map<String, String> fetchInventoryItemIds(String shopName, String shopDomain, String accessToken,
                                                      String productGid) {
        JSONObject variables = new JSONObject();
        variables.put("id", productGid);
        variables.put("first", 100);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, VARIANT_INVENTORY_ITEMS, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject product = data == null ? null : data.getJSONObject("product");
        JSONObject variants = product == null ? null : product.getJSONObject("variants");
        JSONArray nodes = variants == null ? null : variants.getJSONArray("nodes");
        Map<String, String> out = new LinkedHashMap<>();
        if (nodes == null) {
            return out;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node == null) {
                continue;
            }
            String variantId = node.getString("id");
            JSONObject item = node.getJSONObject("inventoryItem");
            String itemId = item == null ? null : item.getString("id");
            if (StringUtils.isNotBlank(variantId) && StringUtils.isNotBlank(itemId)) {
                out.put(variantId, itemId);
            }
        }
        return out;
    }

    private String fetchPrimaryLocationId(String shopName, String shopDomain, String accessToken) {
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRIMARY_LOCATION, new JSONObject());
        JSONObject data = response.getJSONObject("data");
        JSONObject locations = data == null ? null : data.getJSONObject("locations");
        JSONArray nodes = locations == null ? null : locations.getJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new CustomException("Shopify has no active location for inventory, shopName=" + shopName);
        }
        String id = nodes.getJSONObject(0).getString("id");
        if (StringUtils.isBlank(id)) {
            throw new CustomException("Shopify primary location id blank, shopName=" + shopName);
        }
        return id;
    }

    private void ensureInventoryTracked(String shopName, String shopDomain, String accessToken,
                                        String inventoryItemId) {
        JSONObject input = new JSONObject();
        input.put("tracked", true);
        JSONObject variables = new JSONObject();
        variables.put("id", inventoryItemId);
        variables.put("input", input);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, TRACK_INVENTORY_ITEM, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("inventoryItemUpdate");
        if (payload == null) {
            throw new CustomException("Shopify inventoryItemUpdate payload null, shopName=" + shopName);
        }
        assertNoUserErrors(shopName, "inventoryItemUpdate", payload.getJSONArray("userErrors"));
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
