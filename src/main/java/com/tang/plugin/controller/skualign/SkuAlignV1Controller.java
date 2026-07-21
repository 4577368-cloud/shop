package com.tang.plugin.controller.skualign;

import com.tang.plugin.domain.dto.skualign.*;
import com.tang.plugin.service.skualign.SkuAlignV1Service;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * SKU Align V1 API — {@code /api/plugin/sku-align/v1/**}.
 * Legacy {@code /api/plugin/match/sku/**} remains during migration.
 */
@RestController
@RequestMapping("/api/plugin/sku-align/v1")
public class SkuAlignV1Controller {

    @Resource
    private SkuAlignV1Service skuAlignV1Service;

    @GetMapping("/overview")
    public SkuAlignOverviewVO overview(@RequestParam String shopName,
                                       @RequestParam(required = false) String tab) {
        return skuAlignV1Service.overview(shopName, tab);
    }

    @GetMapping("/products/{productId}")
    public SkuAlignProductDetailVO productDetail(@RequestParam String shopName,
                                                 @PathVariable String productId) {
        return skuAlignV1Service.productDetail(shopName, productId);
    }

    @PostMapping("/runs")
    public SkuAlignRunAcceptedVO enqueueRun(@RequestBody SkuAlignRunRequestDTO body) {
        return skuAlignV1Service.enqueueRun(body);
    }

    /** Step 3 — silent page-enter refresh for stale unresolved products. */
    @PostMapping("/page-enter")
    public SkuAlignRunAcceptedVO pageEnter(@RequestParam String shopName) {
        return skuAlignV1Service.triggerPageEnter(shopName);
    }

    /** Step 3 — card expand refresh for a single unresolved product. */
    @PostMapping("/products/{productId}/expand")
    public SkuAlignRunAcceptedVO cardExpand(@RequestParam String shopName,
                                            @PathVariable String productId) {
        return skuAlignV1Service.triggerCardExpand(shopName, productId);
    }

    @GetMapping("/runs/{runId}")
    public SkuAlignRunStatusVO runStatus(@RequestParam String shopName,
                                         @PathVariable long runId) {
        return skuAlignV1Service.runStatus(shopName, runId);
    }

    @PostMapping("/confirm-suggestions")
    public SkuAlignConfirmResultVO confirmSuggestions(@RequestBody SkuAlignConfirmSuggestionsDTO body) {
        return skuAlignV1Service.confirmSuggestions(body);
    }

    @PostMapping("/variants/{variantId}/bind")
    public void manualBind(@PathVariable String variantId,
                           @RequestBody SkuAlignManualBindDTO body) {
        body.setThirdPlatformSkuId(variantId);
        skuAlignV1Service.manualBind(body);
    }

    @PostMapping("/variants/{variantId}/block")
    public void blockVariant(@PathVariable String variantId,
                             @RequestBody SkuAlignBlockVariantDTO body) {
        body.setThirdPlatformSkuId(variantId);
        skuAlignV1Service.blockVariant(body);
    }

    @PostMapping("/products/{productId}/supplement-source")
    public SkuAlignRunAcceptedVO supplementSource(@PathVariable String productId,
                                                  @RequestBody SkuAlignSupplementSourceDTO body) {
        return skuAlignV1Service.addSupplementSource(productId, body);
    }

    @PostMapping("/knowledge/alias")
    public void recordAlias(@RequestBody SkuAlignAliasKnowledgeDTO body) {
        skuAlignV1Service.recordAlias(body);
    }
}
