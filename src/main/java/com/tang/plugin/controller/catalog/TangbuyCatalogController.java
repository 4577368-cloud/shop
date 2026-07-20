package com.tang.plugin.controller.catalog;

import com.tang.plugin.domain.dto.catalog.CatalogRecommendationItem;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import com.tang.plugin.service.pricing.PriceCalculator;
import com.tang.plugin.service.pricing.PricingTemplateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only Tangbuy catalog recommendations (Path B). Public endpoint under /api/plugin/**;
 * returns a lightweight projection with a backend-computed estimatedSalePrice (M1-2). No Shopify
 * calls, no persistence. The effective pricing template comes from the shop (or system default when
 * shopName is absent / unconfigured).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/catalog")
public class TangbuyCatalogController {

    @Resource
    private TangbuyCatalogService tangbuyCatalogService;
    @Resource
    private PricingTemplateService pricingTemplateService;
    @Resource
    private PriceCalculator priceCalculator;

    @GetMapping("/recommendations")
    public List<CatalogRecommendationItem> recommendations(
            @RequestParam(value = "shopName", required = false) String shopName,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "limit", required = false) Integer limit) {
        PricingTemplate template = pricingTemplateService.getEffective(shopName);
        return tangbuyCatalogService.list(offset, limit).stream()
                .map(p -> toItem(p, template))
                .toList();
    }

    /** Total number of catalog entries (for pagination / the "发现新品" total). */
    @GetMapping("/recommendations/count")
    public Map<String, Integer> recommendationsCount() {
        return Map.of("count", tangbuyCatalogService.size());
    }

    /**
     * Ops diagnostic: live vs offline catalog, and a one-row pageInfo probe (never returns the token).
     */
    @GetMapping("/mall-status")
    public Map<String, Object> mallStatus() {
        return tangbuyCatalogService.mallStatus();
    }

    private CatalogRecommendationItem toItem(TangbuyCatalogProduct p, PricingTemplate template) {
        return new CatalogRecommendationItem()
                .setCandidateId(p.getCandidateId())
                .setTitle(p.getTitle())
                .setImageUrl(p.getImageUrl())
                .setImageUrls(p.getImageUrls())
                .setPrice(p.getPrice())
                .setCurrency(p.getCurrency())
                .setEstimatedSalePrice(priceCalculator.calculate(p.getPrice(), template))
                .setTargetCurrency(template.getTargetCurrency())
                .setSupplierShop(p.getSupplierShop())
                .setSkuAttr(p.getSkuAttr())
                .setOfferId1688(p.getOfferId1688())
                .setTangbuyUrl(p.getTangbuyUrl())
                .setUpstreamPlatform(p.getUpstreamPlatform())
                .setBarcode(p.getBarcode());
    }
}
