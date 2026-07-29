package com.tang.plugin.controller.skualign;

import com.tang.plugin.domain.dto.skualign.*;
import com.tang.plugin.service.skualign.SkuAlignV1Service;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
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
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/overview")
    public SkuAlignOverviewVO overview(HttpServletRequest request,
                                       @RequestParam String shopName,
                                       @RequestParam(required = false) String tab) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return skuAlignV1Service.overview(shopName, tab);
    }

    @GetMapping("/products/detail")
    public SkuAlignProductDetailVO productDetail(HttpServletRequest request,
                                                 @RequestParam String shopName,
                                                 @RequestParam String productId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return skuAlignV1Service.productDetail(shopName, productId);
    }

    @PostMapping("/runs")
    public SkuAlignRunAcceptedVO enqueueRun(HttpServletRequest request,
                                           @RequestBody SkuAlignRunRequestDTO body) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return skuAlignV1Service.enqueueRun(body);
    }

    /** Step 3 — silent page-enter refresh for stale unresolved products. */
    @PostMapping("/page-enter")
    public SkuAlignRunAcceptedVO pageEnter(HttpServletRequest request,
                                          @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return skuAlignV1Service.triggerPageEnter(shopName);
    }

    /** Step 3 — card expand refresh for a single unresolved product. */
    @PostMapping("/products/expand")
    public SkuAlignRunAcceptedVO cardExpand(HttpServletRequest request,
                                            @RequestParam String shopName,
                                            @RequestParam String productId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return skuAlignV1Service.triggerCardExpand(shopName, productId);
    }

    @GetMapping("/runs/{runId}")
    public SkuAlignRunStatusVO runStatus(HttpServletRequest request,
                                        @RequestParam String shopName,
                                        @PathVariable long runId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return skuAlignV1Service.runStatus(shopName, runId);
    }

    @PostMapping("/confirm-suggestions")
    public SkuAlignConfirmResultVO confirmSuggestions(HttpServletRequest request,
                                                     @RequestBody SkuAlignConfirmSuggestionsDTO body) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        return skuAlignV1Service.confirmSuggestions(body);
    }

    @PostMapping("/variants/bind")
    public void manualBind(HttpServletRequest request,
                           @RequestParam String variantId,
                           @RequestBody SkuAlignManualBindDTO body) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        body.setThirdPlatformSkuId(variantId);
        skuAlignV1Service.manualBind(body);
    }

    @PostMapping("/variants/block")
    public void blockVariant(HttpServletRequest request,
                             @RequestParam String variantId,
                             @RequestBody SkuAlignBlockVariantDTO body) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        body.setThirdPlatformSkuId(variantId);
        skuAlignV1Service.blockVariant(body);
    }

    @PostMapping("/products/supplement-source")
    public SkuAlignRunAcceptedVO supplementSource(HttpServletRequest request,
                                                  @RequestParam String shopName,
                                                  @RequestParam String productId,
                                                  @RequestBody SkuAlignSupplementSourceDTO body) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        body.setShopName(shopName);
        return skuAlignV1Service.addSupplementSource(productId, body);
    }

    @PostMapping("/knowledge/alias")
    public void recordAlias(HttpServletRequest request,
                            @RequestBody SkuAlignAliasKnowledgeDTO body) {
        // Alias knowledge is shop-scoped; require ownership when shopName is present.
        if (body.getShopName() != null && !body.getShopName().isBlank()) {
            shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), body.getShopName());
        }
        skuAlignV1Service.recordAlias(body);
    }
}
