package com.tang.plugin.service.order;

import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skeleton outer-order service (idempotency lookup + draft save).
 * Replace with real DAO when DB is wired.
 */
@Slf4j
@Service
public class TOrderOuterService {

    private final AtomicLong idSeq = new AtomicLong(1);
    private final Map<String, Long> index = new ConcurrentHashMap<>();

    public List<Long> listOrderIdsByChannelOuterShopNameAndOuterOrderId(
            String channel, String shopName, String outerOrderId) {
        String key = key(channel, shopName, outerOrderId);
        Long id = index.get(key);
        return id == null ? Collections.emptyList() : List.of(id);
    }

    public Long saveDraftOrderSkeleton(PluginShopBO shopBO, ExternalOrder externalOrder) {
        String key = key(shopBO.getShopType().name(), shopBO.getShopName(), externalOrder.getOrderId());
        Long id = idSeq.getAndIncrement();
        index.put(key, id);
        log.info("Saved skeleton draft order id={} shopName={} orderId={}",
                id, shopBO.getShopName(), externalOrder.getOrderId());
        return id;
    }

    private static String key(String channel, String shopName, String outerOrderId) {
        return channel + "|" + shopName + "|" + outerOrderId;
    }
}
