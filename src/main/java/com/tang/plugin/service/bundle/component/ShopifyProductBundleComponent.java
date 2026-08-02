package com.tang.plugin.service.bundle.component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.BundlesFeatureVO;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Shopify Fixed Bundle GraphQL — isolated from publish/match paths for merge safety.
 */
@Slf4j
@Component
public class ShopifyProductBundleComponent {

    private static final String BUNDLES_FEATURE = """
            query BundlesFeature {
              shop {
                features {
                  bundles {
                    eligibleForBundles
                    ineligibilityReason
                    sellsBundles
                  }
                }
              }
            }
            """;

    private static final String PRODUCT_OPTIONS_BY_ID = """
            query ProductOptionsById($id: ID!) {
              product(id: $id) {
                id
                title
                descriptionHtml
                tags
                featuredImage { url altText }
                media(first: 8) {
                  nodes {
                    ... on MediaImage {
                      image { url altText }
                    }
                  }
                }
                options {
                  id
                  name
                  values
                }
                variants(first: 100) {
                  nodes {
                    id
                    title
                    price
                    selectedOptions { name value }
                  }
                }
              }
            }
            """;

    private static final String PRODUCT_UPDATE_FIELDS = """
            mutation BundleParentFields($product: ProductUpdateInput!) {
              productUpdate(product: $product) {
                product { id title status tags }
                userErrors { field message }
              }
            }
            """;

    public static final String KIT_TAG = "tangbuy-kit";

    private static final String PRODUCT_CREATE_MEDIA = """
            mutation BundleParentMedia($productId: ID!, $media: [CreateMediaInput!]!) {
              productCreateMedia(productId: $productId, media: $media) {
                media { id status }
                mediaUserErrors { field message code }
                userErrors { field message }
              }
            }
            """;

    private static final String PRODUCT_BUNDLE_CREATE = """
            mutation ProductBundleCreate($input: ProductBundleCreateInput!) {
              productBundleCreate(input: $input) {
                productBundleOperation {
                  id
                  status
                }
                userErrors { field message }
              }
            }
            """;

    private static final String PRODUCT_BUNDLE_UPDATE = """
            mutation ProductBundleUpdate($input: ProductBundleUpdateInput!) {
              productBundleUpdate(input: $input) {
                productBundleOperation {
                  id
                  status
                }
                userErrors { field message }
              }
            }
            """;

    private static final String PRODUCT_DELETE = """
            mutation BundleParentDelete($input: ProductDeleteInput!) {
              productDelete(input: $input) {
                deletedProductId
                userErrors { field message }
              }
            }
            """;

    private static final String PRODUCT_OPERATION = """
            query ProductBundleOperation($id: ID!) {
              productOperation(id: $id) {
                ... on ProductBundleOperation {
                  id
                  status
                  product {
                    id
                    title
                    variants(first: 5) {
                      nodes { id price }
                    }
                  }
                  userErrors { field message code }
                }
              }
            }
            """;

    private static final String PRODUCT_VARIANTS_BULK_UPDATE = """
            mutation BundleParentPrice($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                userErrors { field message }
              }
            }
            """;

    private static final String METAFIELDS_SET = """
            mutation BundleDiscountMetafield($metafields: [MetafieldsSetInput!]!) {
              metafieldsSet(metafields: $metafields) {
                userErrors { field message }
              }
            }
            """;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    public BundlesFeatureVO fetchBundlesFeature(String shopName, String shopDomain, String accessToken) {
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, BUNDLES_FEATURE, new JSONObject());
        JSONObject data = response.getJSONObject("data");
        JSONObject shop = data == null ? null : data.getJSONObject("shop");
        JSONObject features = shop == null ? null : shop.getJSONObject("features");
        JSONObject bundles = features == null ? null : features.getJSONObject("bundles");
        BundlesFeatureVO vo = new BundlesFeatureVO();
        if (bundles == null) {
            vo.setEligibleForBundles(false);
            vo.setIneligibilityReason("Bundles feature unavailable on this shop");
            vo.setSellsBundles(false);
            return vo;
        }
        vo.setEligibleForBundles(Boolean.TRUE.equals(bundles.getBoolean("eligibleForBundles")));
        vo.setIneligibilityReason(bundles.getString("ineligibilityReason"));
        vo.setSellsBundles(Boolean.TRUE.equals(bundles.getBoolean("sellsBundles")));
        return vo;
    }

    public String createBundle(String shopName, String shopDomain, String accessToken,
                               String title, List<ComponentSpec> components) {
        if (StringUtils.isBlank(title)) {
            throw new CustomException("Bundle title required");
        }
        JSONArray componentInputs = buildComponentInputs(shopName, shopDomain, accessToken, components);
        JSONObject input = new JSONObject();
        input.put("title", title);
        input.put("components", componentInputs);
        JSONObject variables = new JSONObject();
        variables.put("input", input);

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_BUNDLE_CREATE, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productBundleCreate");
        if (payload == null) {
            throw new CustomException("productBundleCreate returned empty payload");
        }
        assertNoUserErrors(payload.getJSONArray("userErrors"), "productBundleCreate");
        JSONObject op = payload.getJSONObject("productBundleOperation");
        if (op == null || StringUtils.isBlank(op.getString("id"))) {
            throw new CustomException("productBundleCreate missing operation id");
        }
        return op.getString("id");
    }

    public String updateBundle(String shopName, String shopDomain, String accessToken,
                               String parentProductId, String title, List<ComponentSpec> components) {
        if (StringUtils.isBlank(parentProductId)) {
            throw new CustomException("parentProductId required for update");
        }
        JSONArray componentInputs = buildComponentInputs(shopName, shopDomain, accessToken, components);
        JSONObject input = new JSONObject();
        input.put("productId", toProductGid(parentProductId));
        if (StringUtils.isNotBlank(title)) {
            input.put("title", title);
        }
        input.put("components", componentInputs);
        JSONObject variables = new JSONObject();
        variables.put("input", input);

        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_BUNDLE_UPDATE, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productBundleUpdate");
        if (payload == null) {
            throw new CustomException("productBundleUpdate returned empty payload");
        }
        assertNoUserErrors(payload.getJSONArray("userErrors"), "productBundleUpdate");
        JSONObject op = payload.getJSONObject("productBundleOperation");
        if (op == null || StringUtils.isBlank(op.getString("id"))) {
            throw new CustomException("productBundleUpdate missing operation id");
        }
        return op.getString("id");
    }

    /** Soft-dissolve: delete the Shopify parent product we created. */
    public void deleteParentProduct(String shopName, String shopDomain, String accessToken,
                                    String parentProductId) {
        if (StringUtils.isBlank(parentProductId)) {
            throw new CustomException("parentProductId required to dissolve");
        }
        JSONObject input = new JSONObject();
        input.put("id", toProductGid(parentProductId));
        JSONObject variables = new JSONObject();
        variables.put("input", input);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_DELETE, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productDelete");
        if (payload == null) {
            throw new CustomException("productDelete returned empty payload");
        }
        assertNoUserErrors(payload.getJSONArray("userErrors"), "productDelete");
    }

    public void updateParentVariantPrice(String shopName, String shopDomain, String accessToken,
                                         String productGid, String variantGid, BigDecimal price) {
        if (price == null || StringUtils.isAnyBlank(productGid, variantGid)) return;
        JSONObject variant = new JSONObject();
        variant.put("id", variantGid.startsWith("gid://") ? variantGid : "gid://shopify/ProductVariant/" + variantGid);
        variant.put("price", price.toPlainString());
        JSONArray variants = new JSONArray();
        variants.add(variant);
        JSONObject variables = new JSONObject();
        variables.put("productId", productGid.startsWith("gid://") ? productGid : toProductGid(productGid));
        variables.put("variants", variants);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_VARIANTS_BULK_UPDATE, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productVariantsBulkUpdate");
        if (payload != null) {
            assertNoUserErrors(payload.getJSONArray("userErrors"), "productVariantsBulkUpdate");
        }
    }

    /** Writes discount % metafield for the Discount Function extension to read. */
    public void setBundleDiscountMetafield(String shopName, String shopDomain, String accessToken,
                                           String parentProductId, BigDecimal discountPercent) {
        if (StringUtils.isBlank(parentProductId)) return;
        JSONObject mf = new JSONObject();
        mf.put("ownerId", toProductGid(parentProductId));
        mf.put("namespace", "tangbuy_bundle");
        mf.put("key", "discount_percent");
        mf.put("type", "number_decimal");
        mf.put("value", discountPercent == null ? "0" : discountPercent.toPlainString());
        JSONArray list = new JSONArray();
        list.add(mf);
        JSONObject variables = new JSONObject();
        variables.put("metafields", list);
        try {
            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, METAFIELDS_SET, variables);
            JSONObject data = response.getJSONObject("data");
            JSONObject payload = data == null ? null : data.getJSONObject("metafieldsSet");
            if (payload != null) {
                assertNoUserErrors(payload.getJSONArray("userErrors"), "metafieldsSet");
            }
        } catch (Exception e) {
            log.warn("Bundle discount metafield skipped shop={} product={}: {}",
                    shopName, parentProductId, e.getMessage());
        }
    }

    /**
     * After bundle parent exists: copy merchandising from context (+ component list),
     * set status ACTIVE, attach gallery images. Best-effort — does not fail the create.
     */
    public void enrichParentMerchandise(String shopName, String shopDomain, String accessToken,
                                        String parentProductId,
                                        String contextProductId,
                                        String parentTitle,
                                        List<ComponentSpec> components) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, parentProductId)) return;
        try {
            JSONObject context = null;
            if (StringUtils.isNotBlank(contextProductId)) {
                context = fetchProductOptions(
                        shopName, shopDomain, accessToken, toProductGid(contextProductId));
            }
            List<String> imageUrls = collectImageUrls(context);
            List<ComponentLine> lines = new ArrayList<>();
            if (components != null) {
                for (ComponentSpec spec : components) {
                    if (spec == null || StringUtils.isBlank(spec.productId())) continue;
                    String title = null;
                    JSONObject p = null;
                    try {
                        p = fetchProductOptions(
                                shopName, shopDomain, accessToken, toProductGid(spec.productId()));
                        if (p != null) title = p.getString("title");
                    } catch (Exception ignored) {
                        /* title optional */
                    }
                    String imageUrl = firstImageUrl(p);
                    lines.add(new ComponentLine(
                            shortDisplayTitle(StringUtils.defaultIfBlank(
                                    title, "Product " + numericProductId(spec.productId()))),
                            Math.max(1, spec.quantity()),
                            imageUrl));
                    if (imageUrls.isEmpty() && p != null) {
                        imageUrls = collectImageUrls(p);
                    }
                }
            }

            String descriptionHtml = buildBundleDescriptionHtml(context);
            JSONObject parentNow = fetchProductOptions(
                    shopName, shopDomain, accessToken, toProductGid(parentProductId));
            JSONObject product = new JSONObject();
            product.put("id", toProductGid(parentProductId));
            if (StringUtils.isNotBlank(parentTitle)) {
                product.put("title", parentTitle.trim());
            }
            if (StringUtils.isNotBlank(descriptionHtml)) {
                product.put("descriptionHtml", descriptionHtml);
            }
            product.put("status", "ACTIVE");
            product.put("tags", mergeKitTag(parentNow == null ? null : parentNow.get("tags")));
            JSONObject variables = new JSONObject();
            variables.put("product", product);
            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, PRODUCT_UPDATE_FIELDS, variables);
            JSONObject data = response.getJSONObject("data");
            JSONObject payload = data == null ? null : data.getJSONObject("productUpdate");
            if (payload != null) {
                assertNoUserErrors(payload.getJSONArray("userErrors"), "productUpdate");
            }

            if (!imageUrls.isEmpty()) {
                if (collectImageUrls(parentNow).isEmpty()) {
                    attachParentImages(shopName, shopDomain, accessToken, parentProductId, imageUrls);
                }
            }
            writeCompositionMetafield(
                    shopName, shopDomain, accessToken, parentProductId, lines);
            setBooleanMetafield(shopName, shopDomain, accessToken,
                    parentProductId, "tangbuy_bundle", "is_kit", true);
            log.info("Bundle parent merchandise enriched shop={} parent={} images={} kitTag=1",
                    shopName, parentProductId, imageUrls.size());
        } catch (Exception e) {
            log.warn("Bundle parent merchandise enrich skipped shop={} parent={}: {}",
                    shopName, parentProductId, e.getMessage());
        }
    }

    /**
     * Best-effort: remove kit tag + is_kit before parent delete on dissolve.
     * Safe if product already gone.
     */
    public void clearKitMarkers(String shopName, String shopDomain, String accessToken,
                                String parentProductId) {
        if (StringUtils.isBlank(parentProductId)) return;
        try {
            JSONObject parent = fetchProductOptions(
                    shopName, shopDomain, accessToken, toProductGid(parentProductId));
            if (parent == null) return;
            JSONObject product = new JSONObject();
            product.put("id", toProductGid(parentProductId));
            product.put("tags", removeKitTag(parent.get("tags")));
            JSONObject variables = new JSONObject();
            variables.put("product", product);
            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, PRODUCT_UPDATE_FIELDS, variables);
            JSONObject data = response.getJSONObject("data");
            JSONObject payload = data == null ? null : data.getJSONObject("productUpdate");
            if (payload != null) {
                assertNoUserErrors(payload.getJSONArray("userErrors"), "productUpdate");
            }
            setBooleanMetafield(shopName, shopDomain, accessToken,
                    parentProductId, "tangbuy_bundle", "is_kit", false);
        } catch (Exception e) {
            log.warn("Bundle clearKitMarkers skipped shop={} parent={}: {}",
                    shopName, parentProductId, e.getMessage());
        }
    }

    /** PDP / theme readable composition list on the kit parent. */
    private void writeCompositionMetafield(String shopName, String shopDomain, String accessToken,
                                           String parentProductId, List<ComponentLine> lines) {
        if (StringUtils.isBlank(parentProductId)) return;
        JSONArray arr = new JSONArray();
        for (ComponentLine line : lines) {
            JSONObject o = new JSONObject();
            o.put("title", line.title());
            o.put("quantity", line.quantity());
            if (StringUtils.isNotBlank(line.imageUrl())) {
                o.put("imageUrl", line.imageUrl());
            }
            arr.add(o);
        }
        setJsonMetafield(shopName, shopDomain, accessToken,
                parentProductId, "tangbuy_bundle", "components_json", arr.toJSONString());
    }

    /** Same-product combo config on the original product (Track B — no new parent). */
    public void writeComboConfigMetafield(String shopName, String shopDomain, String accessToken,
                                          String productId, String configJson) {
        if (StringUtils.isAnyBlank(productId, configJson)) return;
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_combo", "config", configJson);
    }

    /** Gift rule on trigger product (Track gift — separate entry). */
    public void writeGiftRuleMetafield(String shopName, String shopDomain, String accessToken,
                                       String productId, String ruleJson) {
        if (StringUtils.isAnyBlank(productId, ruleJson)) return;
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_gift", "rule", ruleJson);
    }

    /** Mix & Match rule on each pool product (Bundle Hub). */
    public void writeMixRuleMetafield(String shopName, String shopDomain, String accessToken,
                                      String productId, String ruleJson) {
        if (StringUtils.isAnyBlank(productId, ruleJson)) return;
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_mix", "rule", ruleJson);
    }

    public void clearMixRuleMetafield(String shopName, String shopDomain, String accessToken,
                                      String productId) {
        if (StringUtils.isBlank(productId)) return;
        // Empty JSON object marks inactive; Function ignores missing/empty campaignId.
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_mix", "rule", "{}");
    }

    /** BYOB slot template on pool products (multi-line checkout). */
    public void writeByobRuleMetafield(String shopName, String shopDomain, String accessToken,
                                       String productId, String ruleJson) {
        if (StringUtils.isAnyBlank(productId, ruleJson)) return;
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_byob", "rule", ruleJson);
    }

    public void clearByobRuleMetafield(String shopName, String shopDomain, String accessToken,
                                       String productId) {
        if (StringUtils.isBlank(productId)) return;
        setJsonMetafield(shopName, shopDomain, accessToken,
                productId, "tangbuy_byob", "rule", "{}");
    }

    private static String mergeKitTag(Object tagsRaw) {
        java.util.LinkedHashSet<String> tags = parseTags(tagsRaw);
        tags.add(KIT_TAG);
        return String.join(", ", tags);
    }

    private static String removeKitTag(Object tagsRaw) {
        java.util.LinkedHashSet<String> tags = parseTags(tagsRaw);
        tags.remove(KIT_TAG);
        return String.join(", ", tags);
    }

    private static java.util.LinkedHashSet<String> parseTags(Object tagsRaw) {
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        if (tagsRaw instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                String t = arr.getString(i);
                if (StringUtils.isNotBlank(t)) tags.add(t.trim());
            }
        } else if (tagsRaw instanceof String s && StringUtils.isNotBlank(s)) {
            for (String part : s.split(",")) {
                if (StringUtils.isNotBlank(part)) tags.add(part.trim());
            }
        }
        return tags;
    }

    private void setJsonMetafield(String shopName, String shopDomain, String accessToken,
                                  String productId, String namespace, String key, String jsonValue) {
        setTypedMetafield(shopName, shopDomain, accessToken, productId, namespace, key, "json", jsonValue);
    }

    private void setBooleanMetafield(String shopName, String shopDomain, String accessToken,
                                     String productId, String namespace, String key, boolean value) {
        setTypedMetafield(shopName, shopDomain, accessToken, productId, namespace, key,
                "boolean", value ? "true" : "false");
    }

    private void setTypedMetafield(String shopName, String shopDomain, String accessToken,
                                   String productId, String namespace, String key,
                                   String type, String value) {
        JSONObject mf = new JSONObject();
        mf.put("ownerId", toProductGid(productId));
        mf.put("namespace", namespace);
        mf.put("key", key);
        mf.put("type", type);
        mf.put("value", value);
        JSONArray list = new JSONArray();
        list.add(mf);
        JSONObject variables = new JSONObject();
        variables.put("metafields", list);
        try {
            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, METAFIELDS_SET, variables);
            JSONObject data = response.getJSONObject("data");
            JSONObject payload = data == null ? null : data.getJSONObject("metafieldsSet");
            if (payload != null) {
                assertNoUserErrors(payload.getJSONArray("userErrors"), "metafieldsSet");
            }
        } catch (Exception e) {
            log.warn("Bundle metafield {}.{} skipped shop={} product={}: {}",
                    namespace, key, shopName, productId, e.getMessage());
        }
    }

    private void attachParentImages(String shopName, String shopDomain, String accessToken,
                                    String parentProductId, List<String> imageUrls) {
        JSONArray media = new JSONArray();
        int n = 0;
        for (String url : imageUrls) {
            if (StringUtils.isBlank(url)) continue;
            JSONObject m = new JSONObject();
            m.put("originalSource", url.trim());
            m.put("mediaContentType", "IMAGE");
            m.put("alt", "Bundle");
            media.add(m);
            if (++n >= 6) break;
        }
        if (media.isEmpty()) return;
        JSONObject variables = new JSONObject();
        variables.put("productId", toProductGid(parentProductId));
        variables.put("media", media);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_CREATE_MEDIA, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject payload = data == null ? null : data.getJSONObject("productCreateMedia");
        if (payload == null) return;
        JSONArray mediaErrs = payload.getJSONArray("mediaUserErrors");
        if (mediaErrs != null && !mediaErrs.isEmpty()) {
            log.warn("Bundle parent mediaUserErrors shop={} parent={} errs={}",
                    shopName, parentProductId, mediaErrs.toJSONString());
        }
        JSONArray userErrs = payload.getJSONArray("userErrors");
        if (userErrs != null && !userErrs.isEmpty()) {
            log.warn("Bundle parent media userErrors shop={} parent={} errs={}",
                    shopName, parentProductId, userErrs.toJSONString());
        }
    }

    private static List<String> collectImageUrls(JSONObject product) {
        List<String> urls = new ArrayList<>();
        if (product == null) return urls;
        JSONObject featured = product.getJSONObject("featuredImage");
        if (featured != null && StringUtils.isNotBlank(featured.getString("url"))) {
            urls.add(featured.getString("url").trim());
        }
        JSONObject media = product.getJSONObject("media");
        JSONArray nodes = media == null ? null : media.getJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.size(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (node == null) continue;
                JSONObject image = node.getJSONObject("image");
                if (image == null || StringUtils.isBlank(image.getString("url"))) continue;
                String url = image.getString("url").trim();
                if (!urls.contains(url)) urls.add(url);
            }
        }
        return urls;
    }

    /**
     * Kit composition is shown by the theme App Block (metafield). Description only
     * carries the main product's cleaned detail HTML — no duplicate "Bundle includes" list.
     */
    private static String buildBundleDescriptionHtml(JSONObject context) {
        String contextDesc = context == null ? null : context.getString("descriptionHtml");
        if (StringUtils.isBlank(contextDesc)) {
            return null;
        }
        String cleaned = com.tang.plugin.service.publish.support.ProductDescriptionHtmlSanitizer
                .sanitize(contextDesc);
        if (StringUtils.isBlank(cleaned)) {
            return null;
        }
        return "<div class=\"tangbuy-bundle-base-desc\">" + cleaned + "</div>";
    }

    private static String firstImageUrl(JSONObject product) {
        List<String> urls = collectImageUrls(product);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /** Keep PDP kit rows readable — full titles stay on the linked shop products. */
    private static String shortDisplayTitle(String title) {
        String t = StringUtils.trimToEmpty(title);
        if (t.length() <= 48) return t;
        return t.substring(0, 47) + "…";
    }

    private record ComponentLine(String title, int quantity, String imageUrl) {}

    public JSONObject fetchProductOptions(String shopName, String shopDomain, String accessToken, String productGid) {
        JSONObject variables = new JSONObject();
        variables.put("id", productGid);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_OPTIONS_BY_ID, variables);
        JSONObject data = response.getJSONObject("data");
        return data == null ? null : data.getJSONObject("product");
    }

    public JSONObject pollOperation(String shopName, String shopDomain, String accessToken, String operationId) {
        JSONObject variables = new JSONObject();
        variables.put("id", operationId);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, PRODUCT_OPERATION, variables);
        JSONObject data = response.getJSONObject("data");
        return data == null ? null : data.getJSONObject("productOperation");
    }

    private JSONArray buildComponentInputs(String shopName, String shopDomain, String accessToken,
                                           List<ComponentSpec> components) {
        if (components == null || components.isEmpty()) {
            throw new CustomException("Bundle requires at least one component");
        }
        JSONArray componentInputs = new JSONArray();
        for (ComponentSpec spec : components) {
            String gid = toProductGid(spec.productId());
            JSONObject product = fetchProductOptions(shopName, shopDomain, accessToken, gid);
            if (product == null) {
                throw new CustomException("Component product not found: " + spec.productId());
            }
            componentInputs.add(buildComponentInput(product, Math.max(1, spec.quantity()), spec.variantId()));
        }
        return componentInputs;
    }

    private static JSONObject buildComponentInput(JSONObject product, int quantity, String variantId) {
        JSONObject component = new JSONObject();
        component.put("quantity", quantity);
        component.put("productId", product.getString("id"));

        JSONArray options = product.getJSONArray("options");
        JSONArray optionSelections = new JSONArray();

        JSONObject matchedVariant = findVariant(product, variantId);
        if (matchedVariant != null) {
            JSONArray selectedOptions = matchedVariant.getJSONArray("selectedOptions");
            if (selectedOptions != null) {
                for (int i = 0; i < selectedOptions.size(); i++) {
                    JSONObject so = selectedOptions.getJSONObject(i);
                    if (so == null) continue;
                    String name = so.getString("name");
                    String value = so.getString("value");
                    String optionId = findOptionIdByName(options, name);
                    if (StringUtils.isAnyBlank(optionId, name, value)) continue;
                    JSONObject sel = new JSONObject();
                    sel.put("componentOptionId", optionId);
                    sel.put("name", name);
                    JSONArray values = new JSONArray();
                    values.add(value);
                    sel.put("values", values);
                    optionSelections.add(sel);
                }
            }
        }

        if (optionSelections.isEmpty() && options != null) {
            for (int i = 0; i < options.size(); i++) {
                JSONObject opt = options.getJSONObject(i);
                if (opt == null) continue;
                String optionId = opt.getString("id");
                String name = opt.getString("name");
                JSONArray values = opt.getJSONArray("values");
                if (StringUtils.isBlank(optionId) || StringUtils.isBlank(name) || values == null || values.isEmpty()) {
                    continue;
                }
                List<String> valueList = new ArrayList<>();
                for (int v = 0; v < values.size(); v++) {
                    String val = values.getString(v);
                    if (StringUtils.isNotBlank(val)) valueList.add(val);
                }
                if (valueList.isEmpty()) continue;
                JSONObject sel = new JSONObject();
                sel.put("componentOptionId", optionId);
                sel.put("name", name);
                sel.put("values", valueList);
                optionSelections.add(sel);
            }
        }
        if (optionSelections.isEmpty()) {
            throw new CustomException("Component has no selectable options: " + product.getString("id"));
        }
        component.put("optionSelections", optionSelections);
        return component;
    }

    private static JSONObject findVariant(JSONObject product, String variantId) {
        if (StringUtils.isBlank(variantId) || product == null) return null;
        String want = numericProductId(variantId);
        JSONObject variants = product.getJSONObject("variants");
        JSONArray nodes = variants == null ? null : variants.getJSONArray("nodes");
        if (nodes == null) return null;
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject v = nodes.getJSONObject(i);
            if (v == null) continue;
            if (want.equals(numericProductId(v.getString("id")))) return v;
        }
        return null;
    }

    private static String findOptionIdByName(JSONArray options, String name) {
        if (options == null || StringUtils.isBlank(name)) return null;
        for (int i = 0; i < options.size(); i++) {
            JSONObject opt = options.getJSONObject(i);
            if (opt != null && name.equals(opt.getString("name"))) {
                return opt.getString("id");
            }
        }
        return null;
    }

    private static void assertNoUserErrors(JSONArray errors, String op) {
        if (errors == null || errors.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            JSONObject e = errors.getJSONObject(i);
            if (e == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(StringUtils.defaultIfBlank(e.getString("message"), "unknown"));
        }
        throw new CustomException(op + " failed: " + sb);
    }

    public static String toProductGid(String productId) {
        if (StringUtils.isBlank(productId)) {
            throw new CustomException("productId required");
        }
        if (productId.startsWith("gid://")) return productId;
        return "gid://shopify/Product/" + productId.replace("gid://shopify/Product/", "");
    }

    public static String numericProductId(String gidOrId) {
        if (StringUtils.isBlank(gidOrId)) return gidOrId;
        int slash = gidOrId.lastIndexOf('/');
        return slash >= 0 ? gidOrId.substring(slash + 1) : gidOrId;
    }

    public record ComponentSpec(String productId, int quantity, String variantId) {
        public ComponentSpec(String productId, int quantity) {
            this(productId, quantity, null);
        }
    }
}
