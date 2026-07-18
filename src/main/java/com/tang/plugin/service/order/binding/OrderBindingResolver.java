package com.tang.plugin.service.order.binding;

import com.tang.plugin.domain.dto.match.SkuBindingView;
import com.tang.plugin.domain.dto.order.OrderBindingSummary;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ExternalOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.service.match.ProductBindingQueryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Read-only enrichment: resolves each order line's Shopify variant GID against active bindings.
 * Only writes tangbuyProductId / tangbuySkuId / bindingStatus back onto the line — never touches
 * original business fields. Never throws; misses and per-line errors degrade to UNBOUND so order
 * ingestion is never blocked.
 */
@Slf4j
@Component
public class OrderBindingResolver {

    @Resource
    private ProductBindingQueryService productBindingQueryService;

    /**
     * Resolve bindings for all lines of an order. Returns a transient summary for upper-layer logging.
     */
    public OrderBindingSummary resolve(String shopName, ExternalOrder externalOrder) {
        String orderId = externalOrder == null ? null : externalOrder.getOrderId();
        OrderBindingSummary summary = new OrderBindingSummary()
                .setShopName(shopName)
                .setOrderId(orderId);

        if (externalOrder == null || CollectionUtils.isEmpty(externalOrder.getLines())) {
            log.info("Order binding summary shopName={} orderId={} total=0 bound=0 unbound=0",
                    shopName, orderId);
            return summary;
        }

        int bound = 0;
        int unbound = 0;
        for (ExternalOrderLine line : externalOrder.getLines()) {
            if (line == null) {
                continue;
            }
            if (resolveLine(shopName, orderId, line)) {
                bound++;
            } else {
                unbound++;
            }
        }

        summary.setTotal(bound + unbound).setBound(bound).setUnbound(unbound);
        log.info("Order binding summary shopName={} orderId={} total={} bound={} unbound={}",
                shopName, orderId, summary.getTotal(), bound, unbound);
        return summary;
    }

    /**
     * @return true if the line was bound (BOUND), false otherwise (UNBOUND).
     */
    private boolean resolveLine(String shopName, String orderId, ExternalOrderLine line) {
        String variantGid = line.getOuterVariantId();
        if (StringUtils.isBlank(variantGid)) {
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            log.debug("Order line has blank outerVariantId, mark UNBOUND shopName={} orderId={} lineId={}",
                    shopName, orderId, line.getLineId());
            return false;
        }
        try {
            Optional<SkuBindingView> view = productBindingQueryService.findActiveSkuBinding(shopName, variantGid);
            if (view.isPresent()) {
                SkuBindingView binding = view.get();
                line.setTangbuyProductId(binding.getTangbuyProductId());
                line.setTangbuySkuId(binding.getTangbuySkuId());
                line.setBindingStatus(OrderLineBindingStatus.BOUND);
                log.info("Order line BOUND shopName={} orderId={} outerVariantId={} tangbuySkuId={}",
                        shopName, orderId, variantGid, binding.getTangbuySkuId());
                return true;
            }
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            log.debug("Order line UNBOUND (no active binding) shopName={} orderId={} outerVariantId={}",
                    shopName, orderId, variantGid);
            return false;
        } catch (Exception e) {
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            log.error("Order line binding query failed, degrade to UNBOUND shopName={} orderId={} outerVariantId={}",
                    shopName, orderId, variantGid, e);
            return false;
        }
    }
}
