package com.tang.plugin.task;

import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.bo.shopify.ShopifyEnabledShop;
import com.tang.plugin.domain.dto.order.ExternalOrderSyncDTO;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.service.order.ExternalOrderSyncService;
import com.tang.plugin.service.shop.ShopifyEnabledShopProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * Shopify order polling fallback — Spring component (no PowerJob in phase 1).
 */
@Slf4j
@Component
public class ShopifyOrderPollingTask {

    @Resource
    private ExternalOrderSyncService externalOrderSyncService;
    @Resource
    private ShopifyEnabledShopProvider shopifyEnabledShopProvider;
    @Resource(name = "shopOrderSyncExecutor")
    private ThreadPoolExecutor shopOrderSyncExecutor;

    @Value("${tang.plugin.shopify.polling.enabled:false}")
    private boolean pollingEnabled;

    @Scheduled(fixedDelayString = "${tang.plugin.shopify.polling.fixed-delay-ms:300000}")
    public void scheduledPoll() {
        if (!pollingEnabled) {
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
            log.info("ShopifyOrderPollingTask skip, no enabled shops");
            return;
        }

        Set<String> shopNameSet = new HashSet<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        Instant end = Instant.now();
        Instant start = end.minus(60, ChronoUnit.MINUTES);
        long endTime = end.toEpochMilli();
        long startTime = start.toEpochMilli();

        List<CompletableFuture<Void>> futures = shopList.stream()
                .filter(shop -> shopNameSet.add(shop.getShopName()))
                .map(shop -> CompletableFuture.runAsync(() -> {
                    String shopName = shop.getShopName();
                    try {
                        ExternalOrderSyncDTO dto = new ExternalOrderSyncDTO()
                                .setShop(new PluginShopBO()
                                        .setShopName(shopName)
                                        .setShopType(PluginType.SHOPIFY))
                                .setType(2)
                                .setStartTime(startTime)
                                .setEndTime(endTime);
                        externalOrderSyncService.fetchExternalOrderByTimeRange(dto);
                        successCount.incrementAndGet();
                        log.info("Shopify polling success shopName={}", shopName);
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        log.error("Shopify Polling Error for shop[{}]: {}", shopName, e.getMessage(), e);
                    }
                }, shopOrderSyncExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("Shopify 同步完成, 成功:{} 失败:{}", successCount.get(), failCount.get());
    }
}
