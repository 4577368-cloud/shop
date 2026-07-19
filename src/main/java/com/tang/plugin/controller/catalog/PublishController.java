package com.tang.plugin.controller.catalog;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.publish.PublishRequest;
import com.tang.plugin.domain.dto.publish.PublishResultVO;
import com.tang.plugin.service.publish.CatalogPublishLinkService;
import com.tang.plugin.service.publish.CatalogPublishService;
import com.tang.plugin.service.publish.ProductPublishRecordService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publish a single Tangbuy catalog candidate as a new sellable Shopify product (M1-4). Public
 * endpoint under /api/plugin/**; single candidate, no batch. Repeat triggers are idempotent:
 * PUBLISHED short-circuits, PUBLISHING returns in-progress (see CatalogPublishService).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/catalog")
public class PublishController {

    @Resource
    private CatalogPublishService catalogPublishService;
    @Resource
    private ProductPublishRecordService productPublishRecordService;
    @Resource
    private CatalogPublishLinkService catalogPublishLinkService;

    @PostMapping("/publish")
    public PublishResultVO publish(@RequestBody PublishRequest request) {
        if (request == null) {
            throw new CustomException("publish requires a body");
        }
        return catalogPublishService.publish(request.getShopName(), request.getCandidateId());
    }

    /** "已刊登" count: products successfully published (listed) from the Tangbuy catalog for a shop. */
    @GetMapping("/published-count")
    public Map<String, Integer> publishedCount(@RequestParam("shopName") String shopName) {
        return Map.of("count", productPublishRecordService.countPublished(shopName));
    }

    /**
     * One-shot repair: backfill the 1:1 CATALOG bindings for products published before publish-time
     * linking existed. Idempotent — products already linked are left untouched.
     */
    @PostMapping("/link-published")
    public CatalogPublishLinkService.BackfillResult linkPublished(@RequestParam("shopName") String shopName) {
        return catalogPublishLinkService.backfillPublishedBindings(shopName);
    }
}
