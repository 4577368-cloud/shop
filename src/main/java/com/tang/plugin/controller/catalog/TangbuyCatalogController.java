package com.tang.plugin.controller.catalog;

import com.tang.plugin.domain.dto.catalog.CatalogRecommendationItem;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only Tangbuy catalog recommendations (Path B, M1-1). Public endpoint under /api/plugin/**;
 * returns a lightweight projection only. No pricing, no Shopify calls, no persistence yet.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/catalog")
public class TangbuyCatalogController {

    @Resource
    private TangbuyCatalogService tangbuyCatalogService;

    @GetMapping("/recommendations")
    public List<CatalogRecommendationItem> recommendations(
            @RequestParam(value = "shopName", required = false) String shopName,
            @RequestParam(value = "limit", required = false) Integer limit) {
        // shopName is reserved for later steps (published-status join) and intentionally unused here.
        return tangbuyCatalogService.list(limit).stream()
                .map(TangbuyCatalogController::toItem)
                .toList();
    }

    private static CatalogRecommendationItem toItem(TangbuyCatalogProduct p) {
        return new CatalogRecommendationItem()
                .setCandidateId(p.getCandidateId())
                .setTitle(p.getTitle())
                .setImageUrl(p.getImageUrl())
                .setPrice(p.getPrice())
                .setCurrency(p.getCurrency())
                .setSupplierShop(p.getSupplierShop())
                .setSkuAttr(p.getSkuAttr())
                .setOfferId1688(p.getOfferId1688())
                .setTangbuyUrl(p.getTangbuyUrl())
                .setUpstreamPlatform(p.getUpstreamPlatform())
                .setBarcode(p.getBarcode());
    }
}
