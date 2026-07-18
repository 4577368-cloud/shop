package com.tang.plugin.service.order;

import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ExternalOrderLine;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Persists resolver-enriched order lines. Snapshot-only: never re-queries bindings here.
 * Runs inside the caller's order-write transaction (TOrderOuterService.saveDraftOrderSkeleton).
 */
@Slf4j
@Service
public class OrderLinePersistenceService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;

    /**
     * Soft-delete existing lines of the order (by shop_name + outer_order_id) then upsert current ones.
     */
    public void persist(PluginShopBO shopBO, ExternalOrder externalOrder, Long draftOrderId) {
        if (shopBO == null || externalOrder == null) {
            return;
        }
        String shopName = shopBO.getShopName();
        String shopType = shopBO.getShopType() == null ? null : shopBO.getShopType().name();
        String outerOrderId = externalOrder.getOrderId();
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            log.warn("Order line persist skipped, blank shopName/orderId shopName={} orderId={}",
                    shopName, outerOrderId);
            return;
        }

        thirdPlatformOrderLineRepository.softDeleteByOrder(shopName, outerOrderId);

        if (CollectionUtils.isEmpty(externalOrder.getLines())) {
            log.info("Order line persist no lines shopName={} orderId={}", shopName, outerOrderId);
            return;
        }

        int persisted = 0;
        for (ExternalOrderLine line : externalOrder.getLines()) {
            if (line == null) {
                continue;
            }
            if (StringUtils.isBlank(line.getLineId())) {
                log.warn("Order line skipped, blank lineId shopName={} orderId={} outerVariantId={}",
                        shopName, outerOrderId, line.getOuterVariantId());
                continue;
            }
            OrderLineBindingStatus status = line.getBindingStatus() == null
                    ? OrderLineBindingStatus.UNBOUND
                    : line.getBindingStatus();
            ThirdPlatformOrderLine entity = new ThirdPlatformOrderLine()
                    .setShopName(shopName)
                    .setShopType(shopType)
                    .setOuterOrderId(outerOrderId)
                    .setLineId(line.getLineId())
                    .setOuterVariantId(line.getOuterVariantId())
                    .setSku(line.getSku())
                    .setTitle(line.getTitle())
                    .setQuantity(line.getQuantity())
                    .setPrice(line.getPrice())
                    .setTangbuyProductId(line.getTangbuyProductId())
                    .setTangbuySkuId(line.getTangbuySkuId())
                    .setBindingStatus(status)
                    .setDraftOrderId(draftOrderId)
                    .setDelFlag(0);
            thirdPlatformOrderLineRepository.upsert(entity);
            persisted++;
        }
        log.info("Order line persist done shopName={} orderId={} persisted={} draftOrderId={}",
                shopName, outerOrderId, persisted, draftOrderId);
    }
}
