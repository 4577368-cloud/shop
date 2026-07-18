package com.tang.plugin.controller.catalog;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.publish.PublishRequest;
import com.tang.plugin.domain.dto.publish.PublishResultVO;
import com.tang.plugin.service.publish.CatalogPublishService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/publish")
    public PublishResultVO publish(@RequestBody PublishRequest request) {
        if (request == null) {
            throw new CustomException("publish requires a body");
        }
        return catalogPublishService.publish(request.getShopName(), request.getCandidateId());
    }
}
