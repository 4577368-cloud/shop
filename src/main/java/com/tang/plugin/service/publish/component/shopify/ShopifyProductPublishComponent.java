package com.tang.plugin.service.publish.component.shopify;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.publish.ShopifyCreateProductResult;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shopify product create component — GraphQL only. Creates one minimal sellable product via a single
 * {@code productSet(synchronous:true)} call: product (ACTIVE) + one default option + one variant with
 * price, sku, optional barcode and inventory NOT tracked (always purchasable). Optionally enriches
 * with a descriptionHtml and a gallery of images (M1-4.1). No inventory quantities, no multi-variant.
 *
 * <p>Success is judged strictly: top-level errors empty (enforced by {@link ShopifyGraphqlClient}),
 * userErrors empty, product present, first variant present, and both variant id and inventoryItem id
 * present. Any miss throws so the caller takes the failure path. Media is processed asynchronously by
 * Shopify and is intentionally NOT part of the success gate, so a failed/late image never blocks the
 * sellable product (best-effort images).
 */
@Slf4j
@Component
public class ShopifyProductPublishComponent {

    private static final String CREATE_SELLABLE_PRODUCT = """
            mutation CreateSellableProduct($input: ProductSetInput!) {
              productSet(synchronous: true, input: $input) {
                product {
                  id
                  handle
                  variants(first: 1) {
                    nodes { id inventoryItem { id } }
                  }
                }
                userErrors { field message }
              }
            }
            """;

    private static final String DEFAULT_OPTION_NAME = "Title";
    private static final String DEFAULT_OPTION_VALUE = "Default Title";
    private static final int MAX_IMAGES = 10;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    public ShopifyCreateProductResult createSellableProduct(String shopName, String shopDomain,
                                                            String accessToken, String title,
                                                            BigDecimal price, String sku, String barcode,
                                                            String descriptionHtml, List<String> imageUrls) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken)) {
            throw new CustomException("Shopify create product missing credentials, shopName=" + shopName);
        }
        if (StringUtils.isBlank(title)) {
            throw new CustomException("Shopify create product missing title, shopName=" + shopName);
        }
        if (price == null) {
            throw new CustomException("Shopify create product missing price, shopName=" + shopName);
        }

        JSONObject variables = new JSONObject();
        variables.put("input", buildInput(title, price, sku, barcode, descriptionHtml, imageUrls));

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, CREATE_SELLABLE_PRODUCT, variables);
        return parseStrict(shopName, response);
    }

    private static JSONObject buildInput(String title, BigDecimal price, String sku, String barcode,
                                         String descriptionHtml, List<String> imageUrls) {
        JSONObject optionValue = new JSONObject();
        optionValue.put("name", DEFAULT_OPTION_VALUE);
        JSONObject option = new JSONObject();
        option.put("name", DEFAULT_OPTION_NAME);
        option.put("values", JSONArray.of(optionValue));

        JSONObject variantOptionValue = new JSONObject();
        variantOptionValue.put("optionName", DEFAULT_OPTION_NAME);
        variantOptionValue.put("name", DEFAULT_OPTION_VALUE);

        JSONObject inventoryItem = new JSONObject();
        inventoryItem.put("tracked", false);

        JSONObject variant = new JSONObject();
        variant.put("price", price.toPlainString());
        if (StringUtils.isNotBlank(sku)) {
            variant.put("sku", sku);
        }
        if (StringUtils.isNotBlank(barcode)) {
            variant.put("barcode", barcode);
        }
        variant.put("optionValues", JSONArray.of(variantOptionValue));
        variant.put("inventoryItem", inventoryItem);

        JSONObject input = new JSONObject();
        input.put("title", title);
        input.put("status", "ACTIVE");
        if (StringUtils.isNotBlank(descriptionHtml)) {
            input.put("descriptionHtml", descriptionHtml);
        }
        input.put("productOptions", JSONArray.of(option));
        input.put("variants", JSONArray.of(variant));

        List<JSONObject> files = buildImageFiles(title, imageUrls);
        if (!files.isEmpty()) {
            // Gallery; processed asynchronously by Shopify (best-effort, not gated on).
            input.put("files", new JSONArray(files));
        }
        return input;
    }

    private static List<JSONObject> buildImageFiles(String title, List<String> imageUrls) {
        List<JSONObject> files = new ArrayList<>();
        if (imageUrls == null) {
            return files;
        }
        for (String imageUrl : imageUrls) {
            if (!isValidImageUrl(imageUrl) || files.size() >= MAX_IMAGES) {
                continue;
            }
            JSONObject file = new JSONObject();
            file.put("originalSource", imageUrl.trim());
            file.put("contentType", "IMAGE");
            if (StringUtils.isNotBlank(title)) {
                file.put("alt", title);
            }
            files.add(file);
        }
        return files;
    }

    private static boolean isValidImageUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return false;
        }
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static ShopifyCreateProductResult parseStrict(String shopName, JSONObject response) {
        JSONObject data = response.getJSONObject("data");
        JSONObject productSet = data == null ? null : data.getJSONObject("productSet");
        if (productSet == null) {
            throw new CustomException("Shopify productSet payload null, shopName=" + shopName);
        }
        JSONArray userErrors = productSet.getJSONArray("userErrors");
        if (CollectionUtils.isNotEmpty(userErrors)) {
            String joined = userErrors.stream()
                    .map(e -> (JSONObject) e)
                    .map(e -> e.getString("message"))
                    .collect(Collectors.joining("; "));
            throw new CustomException("Shopify productSet userErrors, shopName=" + shopName + ", errors=" + joined);
        }

        JSONObject product = productSet.getJSONObject("product");
        if (product == null) {
            throw new CustomException("Shopify productSet product null, shopName=" + shopName);
        }
        String productId = product.getString("id");
        String handle = product.getString("handle");

        JSONObject variants = product.getJSONObject("variants");
        JSONArray nodes = variants == null ? null : variants.getJSONArray("nodes");
        if (CollectionUtils.isEmpty(nodes)) {
            throw new CustomException("Shopify productSet variant missing, shopName=" + shopName);
        }
        JSONObject variant = nodes.getJSONObject(0);
        String variantId = variant == null ? null : variant.getString("id");
        JSONObject inventoryItem = variant == null ? null : variant.getJSONObject("inventoryItem");
        String inventoryItemId = inventoryItem == null ? null : inventoryItem.getString("id");

        if (StringUtils.isAnyBlank(productId, handle, variantId, inventoryItemId)) {
            throw new CustomException("Shopify productSet incomplete ids, shopName=" + shopName
                    + " productId=" + productId + " handle=" + handle
                    + " variantId=" + variantId + " inventoryItemId=" + inventoryItemId);
        }

        log.info("Shopify product created shopName={} productId={} variantId={}", shopName, productId, variantId);
        return new ShopifyCreateProductResult()
                .setProductId(productId)
                .setHandle(handle)
                .setVariantId(variantId)
                .setInventoryItemId(inventoryItemId);
    }
}
