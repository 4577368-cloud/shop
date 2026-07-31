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
                options {
                  id
                  name
                  values
                }
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

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    private static final String PRODUCT_VARIANTS_BULK_UPDATE = """
            mutation BundleParentPrice($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                userErrors { field message }
              }
            }
            """;

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
            componentInputs.add(buildComponentInput(product, Math.max(1, spec.quantity())));
        }

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

    public void updateParentVariantPrice(String shopName, String shopDomain, String accessToken,
                                         String productGid, String variantGid, java.math.BigDecimal price) {
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

    private static JSONObject buildComponentInput(JSONObject product, int quantity) {
        JSONObject component = new JSONObject();
        component.put("quantity", quantity);
        component.put("productId", product.getString("id"));

        JSONArray options = product.getJSONArray("options");
        JSONArray optionSelections = new JSONArray();
        if (options != null) {
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

    public record ComponentSpec(String productId, int quantity) {}
}
