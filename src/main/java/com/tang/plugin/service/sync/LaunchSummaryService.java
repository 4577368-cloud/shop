package com.tang.plugin.service.sync;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.sync.LaunchSummaryBundleVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.service.logistics.LogisticsAnalysisService;
import com.tang.plugin.service.match.image.ImageMatchConfirmService;
import com.tang.plugin.service.pricing.PricingTemplateService;
import com.tang.plugin.service.skualign.SkuAlignV1Service;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Aggregates workflow mirror reads for the sync / launch ceremony page.
 */
@Slf4j
@Service
public class LaunchSummaryService {

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ImageMatchConfirmService imageMatchConfirmService;
    @Resource
    private SkuAlignV1Service skuAlignV1Service;
    @Resource
    private LogisticsAnalysisService logisticsAnalysisService;
    @Resource
    private PricingTemplateService pricingTemplateService;

    public LaunchSummaryBundleVO aggregate(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("launch-summary requires shopName");
        }
        long started = System.currentTimeMillis();

        CompletableFuture<List<ThirdPlatformProduct>> productsFuture =
                CompletableFuture.supplyAsync(() -> thirdPlatformProductRepository.listByShop(shopName));
        CompletableFuture<List<com.tang.plugin.domain.dto.match.ImageBindingView>> bindingsFuture =
                CompletableFuture.supplyAsync(() -> imageMatchConfirmService.listActiveBindings(shopName));
        CompletableFuture<com.tang.plugin.domain.dto.skualign.SkuAlignOverviewVO> skuFuture =
                CompletableFuture.supplyAsync(() -> skuAlignV1Service.overview(shopName, null));
        CompletableFuture<com.tang.plugin.domain.dto.logistics.LogisticsAnalysisVO> logisticsFuture =
                CompletableFuture.supplyAsync(() -> logisticsAnalysisService.analyze(shopName, false));
        CompletableFuture<com.tang.plugin.domain.dto.pricing.PricingTemplateVO> pricingFuture =
                CompletableFuture.supplyAsync(
                        () -> pricingTemplateService.toVO(pricingTemplateService.getEffective(shopName)));

        CompletableFuture.allOf(
                productsFuture, bindingsFuture, skuFuture, logisticsFuture, pricingFuture
        ).join();

        LaunchSummaryBundleVO bundle = new LaunchSummaryBundleVO()
                .setShopName(shopName)
                .setShopProducts(productsFuture.join())
                .setBindings(bindingsFuture.join())
                .setSkuOverview(skuFuture.join())
                .setLogisticsAnalysis(logisticsFuture.join())
                .setPricingTemplate(pricingFuture.join());

        log.info(
                "Launch summary aggregated shopName={} products={} bindings={} elapsedMs={}",
                shopName,
                bundle.getShopProducts() != null ? bundle.getShopProducts().size() : 0,
                bundle.getBindings() != null ? bundle.getBindings().size() : 0,
                System.currentTimeMillis() - started);
        return bundle;
    }
}
