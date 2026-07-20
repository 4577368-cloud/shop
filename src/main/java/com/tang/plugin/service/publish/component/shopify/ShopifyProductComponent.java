package com.tang.plugin.service.publish.component.shopify;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shopify product network component — GraphQL only, no business conversion.
 * Products-level cursor pagination; variants/media are NOT deep-paginated in P1.
 */
@Slf4j
@Component
public class ShopifyProductComponent {

    private static final String PRODUCTS_BY_UPDATED_AT = """
            query ProductsByUpdatedAt($pageSize: Int!, $cursor: String, $q: String, $variantsFirst: Int!, $mediaFirst: Int!) {
              products(first: $pageSize, after: $cursor, query: $q, sortKey: UPDATED_AT) {
                pageInfo {
                  hasNextPage
                  endCursor
                }
                edges {
                  node {
                    id
                    handle
                    title
                    descriptionHtml
                    status
                    updatedAt
                    featuredImage {
                      url
                      altText
                    }
                    options {
                      name
                      position
                      values
                    }
                    media(first: $mediaFirst) {
                      pageInfo { hasNextPage }
                      edges {
                        node {
                          ... on MediaImage {
                            id
                            image { url altText }
                          }
                        }
                      }
                    }
                    variants(first: $variantsFirst) {
                      pageInfo { hasNextPage }
                      edges {
                        node {
                          id
                          sku
                          title
                          price
                          position
                          selectedOptions { name value }
                          image { url }
                          barcode
                          inventoryQuantity
                          inventoryItem {
                            measurement {
                              weight {
                                value
                                unit
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;
    @Resource
    private ShopifyProperties shopifyProperties;

    /**
     * Fetch product nodes updated since {@code updatedAtMin} (null = full pull), cursor-paginated.
     */
    public ProductFetchResult fetchProducts(String shopName, String shopDomain, String accessToken,
                                            Instant updatedAtMin) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken)) {
            throw new CustomException("Shopify fetchProducts missing credentials, shopName=" + shopName);
        }
        ShopifyProperties.Product cfg = shopifyProperties.getProduct();
        String searchQuery = buildUpdatedAtQuery(updatedAtMin);

        List<JSONObject> products = new ArrayList<>();
        String cursor = null;
        int page = 0;
        boolean truncated = false;

        while (page < cfg.getMaxPages()) {
            page++;
            JSONObject variables = new JSONObject();
            variables.put("pageSize", cfg.getPageSize());
            variables.put("cursor", cursor);
            variables.put("q", searchQuery);
            variables.put("variantsFirst", cfg.getVariantsFirst());
            variables.put("mediaFirst", cfg.getMediaFirst());

            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, PRODUCTS_BY_UPDATED_AT, variables);
            JSONObject data = response.getJSONObject("data");
            if (data == null) {
                throw new CustomException("Shopify products data null, shopName=" + shopName);
            }
            JSONObject productsConn = data.getJSONObject("products");
            if (productsConn == null) {
                log.warn("Shopify products connection null shopName={} page={}", shopName, page);
                break;
            }

            JSONArray edges = productsConn.getJSONArray("edges");
            if (CollectionUtils.isNotEmpty(edges)) {
                for (int i = 0; i < edges.size(); i++) {
                    JSONObject edge = edges.getJSONObject(i);
                    if (edge == null) {
                        continue;
                    }
                    JSONObject node = edge.getJSONObject("node");
                    if (node != null) {
                        warnIfTruncated(shopName, node);
                        products.add(node);
                    }
                }
            }

            JSONObject pageInfo = productsConn.getJSONObject("pageInfo");
            boolean hasNext = pageInfo != null && Boolean.TRUE.equals(pageInfo.getBoolean("hasNextPage"));
            String endCursor = pageInfo == null ? null : pageInfo.getString("endCursor");
            if (!hasNext || StringUtils.isBlank(endCursor)) {
                truncated = false;
                break;
            }
            cursor = endCursor;
            if (page >= cfg.getMaxPages()) {
                truncated = true;
                log.warn("Shopify fetchProducts hit max pages shopName={} pages={} fetched={}",
                        shopName, page, products.size());
            }
        }
        log.info("Shopify fetchProducts done shopName={} fetched={} pages={} truncated={}",
                shopName, products.size(), page, truncated);
        return new ProductFetchResult(products, truncated);
    }

    /**
     * Result of a paginated product pull. {@code truncated} means more pages existed beyond
     * {@code maxPages} — callers must not reconcile deletions against an incomplete catalog.
     */
    public record ProductFetchResult(List<JSONObject> products, boolean truncated) {
    }

    /**
     * P1 does not deep-paginate variants/media; warn so truncation is observable.
     */
    private void warnIfTruncated(String shopName, JSONObject node) {
        String productId = node.getString("id");
        JSONObject variants = node.getJSONObject("variants");
        if (variants != null) {
            JSONObject pi = variants.getJSONObject("pageInfo");
            if (pi != null && Boolean.TRUE.equals(pi.getBoolean("hasNextPage"))) {
                log.warn("Shopify variants truncated (hasNextPage=true) shopName={} productId={}", shopName, productId);
            }
        }
        JSONObject media = node.getJSONObject("media");
        if (media != null) {
            JSONObject pi = media.getJSONObject("pageInfo");
            if (pi != null && Boolean.TRUE.equals(pi.getBoolean("hasNextPage"))) {
                log.warn("Shopify media truncated (hasNextPage=true) shopName={} productId={}", shopName, productId);
            }
        }
    }

    private static String buildUpdatedAtQuery(Instant updatedAtMin) {
        if (updatedAtMin == null) {
            return null;
        }
        return "updated_at:>='" + updatedAtMin.toString() + "'";
    }
}
