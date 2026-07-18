package com.tang.plugin.task;

import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.service.product.ProductSyncService;
import com.tang.plugin.service.shop.ShopifyEnabledShopProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shopify product sync polling fallback — Spring component, default off in P1.
 * Independent from order polling; incremental by updated_at window.
 */
@Slf4j
@Component
public class ShopifyProductPollingTask {

    @Resource
    private ProductSyncService productSyncService;
    @Resource
    private ShopifyEnabledShopProvider shopifyEnabledShopProvider;
    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource(name = "shopOrderSyncExecutor")
    private ThreadPoolExecutor shopOrderSyncExecutor;

    @Scheduled(fixedDelayString = "${tang.plugin.shopify.product.polling-fixed-delay-ms:600000}")
    public void scheduledPoll() {
        if (!shopifyProperties.getProduct().isPollingEnabled()) {
            return;
        }
        runOnce();
    }

    /**
     * Manual / local entry for smoke tests.
     */
    public void runOnce() {
        List<ShopifyEnabledShop> shopList = shopifyEnabledShopProvider.listEnabled();
        if (shopList.isEmpty()) {
            log.info("ShopifyProductPollingTask skip, no enabled shops");
            return;
        }

        Instant updatedAtMin = Instant.now()
                .minus(shopifyProperties.getProduct().getPollingWindowMinutes(), ChronoUnit.MINUTES);
        Set<String> shopNameSet = new HashSet<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = shopList.stream()
                .filter(shop -> shopNameSet.add(shop.getShopName()))
                .map(shop -> CompletableFuture.runAsync(() -> {
                    String shopName = shop.getShopName();
                    try {
                        productSyncService.syncShopifyByShopName(shopName, updatedAtMin);
                        successCount.incrementAndGet();
                        log.info("Shopify product polling success shopName={}", shopName);
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        log.error("Shopify product polling error shopName={}: {}", shopName, e.getMessage(), e);
                    }
                }, shopOrderSyncExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Shopify product sync done, success:{} fail:{}", successCount.get(), failCount.get());
    }
}
