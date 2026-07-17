package com.tang.plugin.service.order.external.component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
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
 * Shopify order network component — GraphQL only. No business conversion.
 */
@Slf4j
@Component
public class ShopifyOrderComponent {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final int LINE_ITEM_FIRST = 100;

    private static final String ORDER_FIELDS = """
            id
            name
            createdAt
            updatedAt
            email
            phone
            cancelledAt
            displayFinancialStatus
            displayFulfillmentStatus
            totalPriceSet {
              shopMoney {
                amount
                currencyCode
              }
            }
            shippingAddress {
              address1
              address2
              city
              provinceCode
              countryCodeV2
              zip
              phone
            }
            lineItems(first: %d) {
              edges {
                node {
                  id
                  name
                  sku
                  quantity
                  originalUnitPriceSet {
                    shopMoney {
                      amount
                      currencyCode
                    }
                  }
                  variant {
                    id
                    title
                  }
                }
              }
            }
            """.formatted(LINE_ITEM_FIRST);

    private static final String ORDERS_BY_UPDATED_AT = """
            query OrdersByUpdatedAt($first: Int!, $after: String, $query: String!) {
              orders(first: $first, after: $after, query: $query, sortKey: UPDATED_AT) {
                pageInfo {
                  hasNextPage
                  endCursor
                }
                edges {
                  node {
                    %s
                  }
                }
              }
            }
            """.formatted(ORDER_FIELDS);

    private static final String ORDER_BY_ID = """
            query OrderById($id: ID!) {
              order(id: $id) {
                %s
              }
            }
            """.formatted(ORDER_FIELDS);

    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;

    /**
     * Fetch order nodes in [startTimeMs, endTimeMs] filtered by updated_at (cursor pagination).
     */
    public List<JSONObject> fetchOrders(String shopName, String shopDomain, String accessToken,
                                        Long startTimeMs, Long endTimeMs) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken)) {
            throw new CustomException("Shopify fetchOrders missing credentials, shopName=" + shopName);
        }
        if (startTimeMs == null || endTimeMs == null || startTimeMs > endTimeMs) {
            throw new CustomException("Shopify fetchOrders invalid time range, shopName=" + shopName);
        }

        String searchQuery = buildUpdatedAtQuery(startTimeMs, endTimeMs);
        List<JSONObject> orders = new ArrayList<>();
        String after = null;
        int page = 0;

        while (page < MAX_PAGES) {
            page++;
            JSONObject variables = new JSONObject();
            variables.put("first", PAGE_SIZE);
            variables.put("after", after);
            variables.put("query", searchQuery);

            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, ORDERS_BY_UPDATED_AT, variables);
            JSONObject data = response.getJSONObject("data");
            if (data == null) {
                throw new CustomException("Shopify orders data null, shopName=" + shopName);
            }
            JSONObject ordersConn = data.getJSONObject("orders");
            if (ordersConn == null) {
                log.warn("Shopify orders connection null shopName={} page={}", shopName, page);
                break;
            }

            JSONArray edges = ordersConn.getJSONArray("edges");
            if (CollectionUtils.isNotEmpty(edges)) {
                for (int i = 0; i < edges.size(); i++) {
                    JSONObject edge = edges.getJSONObject(i);
                    if (edge == null) {
                        continue;
                    }
                    JSONObject node = edge.getJSONObject("node");
                    if (node != null) {
                        orders.add(node);
                    }
                }
            }

            JSONObject pageInfo = ordersConn.getJSONObject("pageInfo");
            boolean hasNext = pageInfo != null && Boolean.TRUE.equals(pageInfo.getBoolean("hasNextPage"));
            String endCursor = pageInfo == null ? null : pageInfo.getString("endCursor");
            if (!hasNext || StringUtils.isBlank(endCursor)) {
                break;
            }
            after = endCursor;
        }

        if (page >= MAX_PAGES) {
            log.warn("Shopify fetchOrders hit max pages shopName={} pages={} fetched={}",
                    shopName, page, orders.size());
        }
        log.info("Shopify fetchOrders done shopName={} fetched={} pages={}", shopName, orders.size(), page);
        return orders;
    }

    /**
     * Backfill a single order by GraphQL GID for webhook handlers.
     */
    public JSONObject fetchOrderById(String shopName, String shopDomain, String accessToken, String orderGid) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken, orderGid)) {
            throw new CustomException("Shopify fetchOrderById missing args, shopName=" + shopName
                    + ", orderId=" + orderGid);
        }
        JSONObject variables = new JSONObject();
        variables.put("id", orderGid);
        JSONObject response = shopifyGraphqlClient.execute(
                shopName, shopDomain, accessToken, ORDER_BY_ID, variables);
        JSONObject data = response.getJSONObject("data");
        JSONObject order = data == null ? null : data.getJSONObject("order");
        if (order == null) {
            log.error("Shopify fetchOrderById empty shopName={} orderId={}", shopName, orderGid);
            throw new CustomException("Shopify order not found, shopName=" + shopName + ", orderId=" + orderGid);
        }
        log.info("Shopify fetchOrderById ok shopName={} orderId={}", shopName, orderGid);
        return order;
    }

    private static String buildUpdatedAtQuery(long startTimeMs, long endTimeMs) {
        String start = Instant.ofEpochMilli(startTimeMs).toString();
        String end = Instant.ofEpochMilli(endTimeMs).toString();
        return "updated_at:>='" + start + "' updated_at:<='" + end + "'";
    }
}
