package com.tang.plugin.service.order;

import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.repository.ThirdPlatformOrderRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Outer-order service: JDBC-persisted order header (idempotency lookup + draft save).
 * Header idempotency key = (shop_type, shop_name, outer_order_id). Order lines persist via
 * OrderLinePersistenceService within the caller's transaction.
 */
@Slf4j
@Service
public class TOrderOuterService {

    @Resource
    private ThirdPlatformOrderRepository thirdPlatformOrderRepository;
    @Resource
    private OrderLinePersistenceService orderLinePersistenceService;

    public List<Long> listOrderIdsByChannelOuterShopNameAndOuterOrderId(
            String channel, String shopName, String outerOrderId) {
        return thirdPlatformOrderRepository.listIdsByKey(channel, shopName, outerOrderId);
    }

    public Long saveDraftOrderSkeleton(PluginShopBO shopBO, ExternalOrder externalOrder) {
        String shopType = shopBO.getShopType() == null ? null : shopBO.getShopType().name();
        ThirdPlatformOrder header = new ThirdPlatformOrder()
                .setShopName(shopBO.getShopName())
                .setShopType(shopType)
                .setOuterOrderId(externalOrder.getOrderId())
                .setOrderName(externalOrder.getOrderName())
                .setFinancialStatus(externalOrder.getFinancialStatus())
                .setFulfillmentStatus(externalOrder.getFulfillmentStatus())
                .setCurrency(externalOrder.getCurrency())
                .setTotalPrice(externalOrder.getTotalPrice())
                .setPlatformCreatedAt(externalOrder.getCreatedAt())
                .setPlatformUpdatedAt(externalOrder.getUpdatedAt())
                .setDelFlag(0);

        Long id = thirdPlatformOrderRepository.saveIfAbsent(header);
        log.info("Saved draft order header id={} shopName={} orderId={}",
                id, shopBO.getShopName(), externalOrder.getOrderId());
        orderLinePersistenceService.persist(shopBO, externalOrder, id);
        return id;
    }
}
