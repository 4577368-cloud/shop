package com.tang.plugin.service.publish.component.shopify;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.publish.PublishOptionValue;
import com.tang.plugin.domain.dto.publish.ShopifyCreateProductResult;
import com.tang.plugin.domain.dto.publish.ShopifyVariantCreateInput;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shopify product create component — GraphQL only. Creates a sellable product via
 * {@code productSet(synchronous:true)}: ACTIVE product + options/variants (1..N) with inventory NOT
 * tracked. Optionally enriches with descriptionHtml and a gallery (best-effort, not gated on).
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
                  variants(first: 100) {
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
    private static final int MAX_OPTIONS = 3;
    private static final int MAX_VARIANTS = 100;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    public ShopifyCreateProductResult createSellableProduct(String shopName, String shopDomain,
                                                            String accessToken, String title,
                                                            String descriptionHtml, List<String> imageUrls,
                                                            List<ShopifyVariantCreateInput> variants) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken)) {
            throw new CustomException("Shopify create product missing credentials, shopName=" + shopName);
        }
        if (StringUtils.isBlank(title)) {
            throw new CustomException("Shopify create product missing title, shopName=" + shopName);
        }
        if (CollectionUtils.isEmpty(variants)) {
            throw new CustomException("Shopify create product missing variants, shopName=" + shopName);
        }
        for (ShopifyVariantCreateInput v : variants) {
            if (v.getSalePrice() == null) {
                throw new CustomException("Shopify create product missing variant price, shopName=" + shopName);
            }
        }

        JSONObject variables = new JSONObject();
        variables.put("input", buildInput(title, descriptionHtml, imageUrls, variants));

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, CREATE_SELLABLE_PRODUCT, variables);
        return parseStrict(shopName, response);
    }

    private static JSONObject buildInput(String title, String descriptionHtml, List<String> imageUrls,
                                         List<ShopifyVariantCreateInput> variants) {
        if (useDefaultSingleVariant(variants)) {
            ShopifyVariantCreateInput v = variants.get(0);
            return buildDefaultSingleVariantInput(title, descriptionHtml, imageUrls, v);
        }
        return buildMultiVariantInput(title, descriptionHtml, imageUrls, variants);
    }

    private static boolean useDefaultSingleVariant(List<ShopifyVariantCreateInput> variants) {
        if (variants.size() != 1) {
            return false;
        }
        List<PublishOptionValue> opts = variants.get(0).getOptionValues();
        return CollectionUtils.isEmpty(opts);
    }

    private static JSONObject buildDefaultSingleVariantInput(String title, String descriptionHtml,
                                                             List<String> imageUrls,
                                                             ShopifyVariantCreateInput v) {
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
        variant.put("price", v.getSalePrice().toPlainString());
        if (StringUtils.isNotBlank(v.getSku())) {
            variant.put("sku", v.getSku());
        }
        if (StringUtils.isNotBlank(v.getBarcode())) {
            variant.put("barcode", v.getBarcode());
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
        attachFiles(input, title, imageUrls);
        return input;
    }

    private static JSONObject buildMultiVariantInput(String title, String descriptionHtml,
                                                     List<String> imageUrls,
                                                     List<ShopifyVariantCreateInput> variants) {
        List<ShopifyVariantCreateInput> capped = variants.size() > MAX_VARIANTS
                ? variants.subList(0, MAX_VARIANTS)
                : variants;

        LinkedHashMap<String, LinkedHashSet<String>> optionMap = new LinkedHashMap<>();
        for (ShopifyVariantCreateInput v : capped) {
            if (CollectionUtils.isEmpty(v.getOptionValues())) {
                continue;
            }
            for (PublishOptionValue ov : v.getOptionValues()) {
                if (ov == null || StringUtils.isAnyBlank(ov.getOptionName(), ov.getValue())) {
                    continue;
                }
                optionMap.computeIfAbsent(ov.getOptionName().trim(), k -> new LinkedHashSet<>())
                        .add(ov.getValue().trim());
            }
        }

        if (optionMap.isEmpty()) {
            return buildDefaultSingleVariantInput(title, descriptionHtml, imageUrls, capped.get(0));
        }

        List<String> optionNames = new ArrayList<>(optionMap.keySet());
        if (optionNames.size() > MAX_OPTIONS) {
            optionNames = optionNames.subList(0, MAX_OPTIONS);
        }
        Set<String> allowedOptions = Set.copyOf(optionNames);

        JSONArray productOptions = new JSONArray();
        for (String optionName : optionNames) {
            JSONObject option = new JSONObject();
            option.put("name", optionName);
            JSONArray values = new JSONArray();
            for (String value : optionMap.get(optionName)) {
                JSONObject vo = new JSONObject();
                vo.put("name", value);
                values.add(vo);
            }
            option.put("values", values);
            productOptions.add(option);
        }

        JSONArray variantArray = new JSONArray();
        Set<String> seenCombos = new LinkedHashSet<>();
        for (ShopifyVariantCreateInput v : capped) {
            JSONArray optionValues = new JSONArray();
            List<String> comboParts = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(v.getOptionValues())) {
                for (PublishOptionValue ov : v.getOptionValues()) {
                    if (ov == null || StringUtils.isAnyBlank(ov.getOptionName(), ov.getValue())) {
                        continue;
                    }
                    String name = ov.getOptionName().trim();
                    String value = ov.getValue().trim();
                    if (!allowedOptions.contains(name)) {
                        continue;
                    }
                    JSONObject ovv = new JSONObject();
                    ovv.put("optionName", name);
                    ovv.put("name", value);
                    optionValues.add(ovv);
                    comboParts.add(name + "=" + value);
                }
            }
            if (optionValues.isEmpty()) {
                continue;
            }
            String comboKey = String.join("|", comboParts);
            if (!seenCombos.add(comboKey)) {
                continue;
            }

            JSONObject inventoryItem = new JSONObject();
            inventoryItem.put("tracked", false);

            JSONObject variant = new JSONObject();
            variant.put("price", v.getSalePrice().toPlainString());
            if (StringUtils.isNotBlank(v.getSku())) {
                variant.put("sku", v.getSku());
            }
            if (StringUtils.isNotBlank(v.getBarcode())) {
                variant.put("barcode", v.getBarcode());
            }
            variant.put("optionValues", optionValues);
            variant.put("inventoryItem", inventoryItem);
            variantArray.add(variant);
        }

        if (variantArray.isEmpty()) {
            return buildDefaultSingleVariantInput(title, descriptionHtml, imageUrls, capped.get(0));
        }

        JSONObject input = new JSONObject();
        input.put("title", title);
        input.put("status", "ACTIVE");
        if (StringUtils.isNotBlank(descriptionHtml)) {
            input.put("descriptionHtml", descriptionHtml);
        }
        input.put("productOptions", productOptions);
        input.put("variants", variantArray);
        attachFiles(input, title, imageUrls);
        return input;
    }

    private static void attachFiles(JSONObject input, String title, List<String> imageUrls) {
        List<JSONObject> files = buildImageFiles(title, imageUrls);
        if (!files.isEmpty()) {
            input.put("files", new JSONArray(files));
        }
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

        log.info("Shopify product created shopName={} productId={} variantCount={} firstVariantId={}",
                shopName, productId, nodes.size(), variantId);
        return new ShopifyCreateProductResult()
                .setProductId(productId)
                .setHandle(handle)
                .setVariantId(variantId)
                .setInventoryItemId(inventoryItemId);
    }
}
