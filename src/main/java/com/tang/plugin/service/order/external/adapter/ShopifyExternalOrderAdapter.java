package com.tang.plugin.service.order.external.adapter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.component.OuterUniqueComponent;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ExternalOrderLine;
import com.tang.plugin.service.remote.RemoteResourceSdkClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts Shopify GraphQL order node JSON into internal ExternalOrder.
 */
@Slf4j
@Component
public class ShopifyExternalOrderAdapter {

    @Resource
    private OuterUniqueComponent outerUniqueComponent;
    @Resource
    private RemoteResourceSdkClient remoteResourceSdkClient;

    public ExternalOrder convertToExternalOrder(JSONObject platformOrder, String shopName) {
        if (platformOrder == null) {
            throw new IllegalArgumentException("platformOrder is null, shopName=" + shopName);
        }

        String orderId = platformOrder.getString("id");
        ExternalOrder order = new ExternalOrder()
                .setOrderId(orderId)
                .setOrderName(platformOrder.getString("name"))
                .setFinancialStatus(platformOrder.getString("displayFinancialStatus"))
                .setFulfillmentStatus(platformOrder.getString("displayFulfillmentStatus"))
                .setEmail(platformOrder.getString("email"))
                .setPhone(platformOrder.getString("phone"))
                .setCreatedAt(parseInstant(platformOrder.getString("createdAt")))
                .setUpdatedAt(parseInstant(platformOrder.getString("updatedAt")))
                .setCancelled(StringUtils.isNotBlank(platformOrder.getString("cancelledAt")));

        applyMoney(order, platformOrder.getJSONObject("totalPriceSet"));
        applyAddress(order, platformOrder.getJSONObject("shippingAddress"));
        applyFinancialFlags(order);
        order.setLines(convertLines(platformOrder, shopName, orderId));

        log.info("Shopify adapter converted shopName={} orderId={} lines={}",
                shopName, orderId, order.getLines().size());
        return order;
    }

    private void applyMoney(ExternalOrder order, JSONObject totalPriceSet) {
        if (totalPriceSet == null) {
            return;
        }
        JSONObject shopMoney = totalPriceSet.getJSONObject("shopMoney");
        if (shopMoney == null) {
            return;
        }
        order.setCurrency(shopMoney.getString("currencyCode"));
        order.setTotalPrice(parseDecimal(shopMoney.getString("amount")));
    }

    private void applyAddress(ExternalOrder order, JSONObject shippingAddress) {
        if (shippingAddress == null) {
            return;
        }
        order.setAddress1(shippingAddress.getString("address1"));
        order.setAddress2(shippingAddress.getString("address2"));
        order.setCity(shippingAddress.getString("city"));
        order.setProvinceCode(shippingAddress.getString("provinceCode"));
        order.setZip(shippingAddress.getString("zip"));
        if (StringUtils.isBlank(order.getPhone())) {
            order.setPhone(shippingAddress.getString("phone"));
        }

        String countryCode = shippingAddress.getString("countryCodeV2");
        if (StringUtils.isBlank(countryCode)) {
            countryCode = shippingAddress.getString("countryCode");
        }
        order.setCountryCode(countryCode);
        if (StringUtils.isNotBlank(countryCode)) {
            RemoteResourceSdkClient.DataRegion region =
                    remoteResourceSdkClient.getDataRegionByCountryCode(countryCode);
            if (region != null) {
                order.setCountryId(region.getCountryId());
            }
        }
    }

    private void applyFinancialFlags(ExternalOrder order) {
        String financial = StringUtils.defaultString(order.getFinancialStatus()).toUpperCase(Locale.ROOT);
        order.setVoided("VOIDED".equals(financial));
        order.setFullyRefunded("REFUNDED".equals(financial));
    }

    private List<ExternalOrderLine> convertLines(JSONObject platformOrder, String shopName, String orderId) {
        List<ExternalOrderLine> lines = new ArrayList<>();
        JSONObject lineItems = platformOrder.getJSONObject("lineItems");
        if (lineItems == null) {
            return lines;
        }
        JSONArray edges = lineItems.getJSONArray("edges");
        if (edges == null || edges.isEmpty()) {
            return lines;
        }
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            if (edge == null) {
                continue;
            }
            JSONObject node = edge.getJSONObject("node");
            if (node == null) {
                continue;
            }
            String outerLineId = node.getString("id");
            String safeLineId = outerUniqueComponent.generateShopifyOrderLineUnique(shopName, outerLineId);

            ExternalOrderLine line = new ExternalOrderLine()
                    .setLineId(safeLineId)
                    .setSku(node.getString("sku"))
                    .setTitle(node.getString("name"))
                    .setQuantity(node.getInteger("quantity"));

            JSONObject variant = node.getJSONObject("variant");
            if (variant != null) {
                line.setOuterVariantId(variant.getString("id"));
                line.setVariantTitle(variant.getString("title"));
            }

            JSONObject unitPriceSet = node.getJSONObject("originalUnitPriceSet");
            if (unitPriceSet != null) {
                JSONObject shopMoney = unitPriceSet.getJSONObject("shopMoney");
                if (shopMoney != null) {
                    line.setPrice(parseDecimal(shopMoney.getString("amount")));
                }
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            log.warn("Shopify adapter no line items shopName={} orderId={}", shopName, orderId);
        }
        return lines;
    }

    private static Instant parseInstant(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Instant.parse(value);
    }

    private static BigDecimal parseDecimal(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return new BigDecimal(value);
    }
}
