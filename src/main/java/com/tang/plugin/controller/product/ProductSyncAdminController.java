package com.tang.plugin.controller.product;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.product.ShopProductDetailVO;
import com.tang.plugin.domain.dto.product.ShopProductUpdateRequest;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.service.product.ProductSyncService;
import com.tang.plugin.service.product.ShopProductQueryService;
import com.tang.plugin.service.product.ShopProductWriteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal integration endpoints for on-demand Shopify product sync and mirror reads.
 * Scheduled polling stays a separate, config-gated fallback.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/product")
public class ProductSyncAdminController {

    @Resource
    private ProductSyncService productSyncService;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ShopProductQueryService shopProductQueryService;
    @Resource
    private ShopProductWriteService shopProductWriteService;

    /**
     * Trigger a one-shot product pull from Shopify into the mirror.
     * {@code windowMinutes > 0} pulls incrementally (updated_at >= now - window); omit for a full pull.
     */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam String shopName,
                                    @RequestParam(required = false) Integer windowMinutes) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("sync requires shopName");
        }
        Instant updatedAtMin = (windowMinutes != null && windowMinutes > 0)
                ? Instant.now().minus(windowMinutes, ChronoUnit.MINUTES)
                : null;
        log.info("Manual product sync requested shopName={} windowMinutes={}", shopName, windowMinutes);
        productSyncService.syncShopifyByShopName(shopName, updatedAtMin);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("shopName", shopName);
        body.put("mode", updatedAtMin == null ? "FULL" : "INCREMENTAL");
        body.put("windowMinutes", windowMinutes);
        body.put("productCount", thirdPlatformProductRepository.countByShop(shopName));
        return body;
    }

    /**
     * List the mirrored SPU rows for a shop (read-only visibility of what sync persisted).
     */
    @GetMapping("/list")
    public List<ThirdPlatformProduct> list(@RequestParam String shopName) {
        return thirdPlatformProductRepository.listByShop(shopName);
    }

    /**
     * Phase 1 read-only detail: SPU + variants + media from the local mirror.
     */
    @GetMapping("/detail")
    public ShopProductDetailVO detail(@RequestParam String shopName,
                                      @RequestParam String itemId) {
        return shopProductQueryService.getDetail(shopName, itemId);
    }

    /**
     * Phase 2/3 write-back: update Shopify product fields, then refresh the local mirror.
     * Body may include {@code expectedUpdatedAt} for optimistic concurrency (409 on mismatch)
     * and {@code force=true} to overwrite.
     */
    @PutMapping("/detail")
    public ShopProductDetailVO update(@RequestParam String shopName,
                                      @RequestBody ShopProductUpdateRequest body) {
        return shopProductWriteService.update(shopName, body);
    }
}
