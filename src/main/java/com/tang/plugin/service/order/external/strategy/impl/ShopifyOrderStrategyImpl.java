package com.tang.plugin.service.order.external.strategy.impl;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.dto.order.ExternalOrderSyncDTO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.service.order.external.adapter.ShopifyExternalOrderAdapter;
import com.tang.plugin.service.order.external.component.ShopifyOrderComponent;
import com.tang.plugin.service.order.external.strategy.BaseExternalOrderStrategy;
import com.tang.plugin.service.order.external.strategy.ExternalOrderStrategy;
import com.tang.plugin.service.shop.ShopifyEnabledShopProvider;
import com.tang.plugin.utils.OrderBizUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shopify order sync strategy — network via Component, mapping via Adapter, lock/idempotency via base.
 */
@Slf4j
@Component
public class ShopifyOrderStrategyImpl
        extends BaseExternalOrderStrategy<ExternalOrder>
        implements ExternalOrderStrategy<ExternalOrder> {

    @Resource
    private ShopifyOrderComponent shopifyOrderComponent;
    @Resource
    private ShopifyExternalOrderAdapter shopifyExternalOrderAdapter;
    @Resource
    private ShopifyEnabledShopProvider shopifyEnabledShopProvider;

    @Override
    public PluginType getPluginType() {
        return PluginType.SHOPIFY;
    }

    @Override
    public Class<ExternalOrder> getOrderClass() {
        return ExternalOrder.class;
    }

    @Override
    public void fetchExternalOrderByTimeRange(ExternalOrderSyncDTO syncDTO) {
        PluginShopBO shopBO = syncDTO == null ? null : syncDTO.getShop();
        if (shopBO == null || StringUtils.isBlank(shopBO.getShopName())) {
            log.error("Shopify sync failed, shopName is empty");
            return;
        }
        String shopName = shopBO.getShopName();
        ShopifyEnabledShop enabledShop = shopifyEnabledShopProvider.findByShopName(shopName)
                .orElseThrow(() -> new CustomException(
                        "Shopify credentials not found for shopName=" + shopName));

        List<JSONObject> orders = shopifyOrderComponent.fetchOrders(
                shopName,
                enabledShop.getShopDomain(),
                enabledShop.getAccessToken(),
                syncDTO.getStartTime(),
                syncDTO.getEndTime());

        if (CollectionUtils.isEmpty(orders)) {
            log.info("Shopify fetchExternalOrderByTimeRange empty shopName={}", shopName);
            return;
        }

        for (JSONObject orderNode : orders) {
            String orderId = orderNode == null ? null : orderNode.getString("id");
            try {
                ExternalOrder externalOrder =
                        shopifyExternalOrderAdapter.convertToExternalOrder(orderNode, shopName);
                upsertDraftOrderFromExternal(externalOrder, shopBO);
            } catch (Exception e) {
                log.error("Shopify fetchExternalOrderByTimeRange error shopName={} orderId={}",
                        shopName, orderId, e);
            }
        }
    }

    /**
     * Webhook ingest: lock + status filter + idempotent create (reuse base write path).
     */
    public Long ingestExternalOrder(ExternalOrder externalOrder, PluginShopBO shopBO) {
        if (externalOrder == null || shopBO == null) {
            return null;
        }
        String shopName = shopBO.getShopName();
        String orderId = externalOrder.getOrderId();
        String lockKey = OrderBizUtils.operationLockKey(orderId);
        return redisManager.lockAround(lockKey, () -> {
            try {
                assertCreatable(externalOrder, shopBO);
            } catch (CustomException e) {
                log.warn("Shopify ingest skip blocked shopName={} orderId={} reason={}",
                        shopName, orderId, e.getMessage());
                return null;
            }
            List<Long> existing = tOrderOuterService.listOrderIdsByChannelOuterShopNameAndOuterOrderId(
                    shopBO.getShopType().name(), shopName, orderId);
            if (CollectionUtils.isNotEmpty(existing)) {
                log.info("Shopify ingest idempotent hit shopName={} orderId={} draftId={}",
                        shopName, orderId, existing.get(0));
                return existing.get(0);
            }
            Long draftId = createDraftOrder(externalOrder, shopBO, null);
            log.info("Shopify ingest created shopName={} orderId={} draftId={}", shopName, orderId, draftId);
            return draftId;
        });
    }
}
