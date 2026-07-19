package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.image.ImageUploadResultVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import com.tang.plugin.service.match.image.Alibaba1688ImageSearchClient;
import com.tang.plugin.service.match.image.Alibaba1688ImageUploadClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A3-3a read-only debug endpoints for the official 1688 cross-border image APIs (AOP), used to verify the
 * contract live before A3-3b swaps {@code ImageSearchService} off the Newton gateway. No persistence, not
 * wired into the product cards yet. Public under {@code /api/plugin/**} (outside the procurement guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match/image-aop")
public class AopImageSearchController {

    @Resource
    private Alibaba1688ImageSearchClient imageSearchClient;
    @Resource
    private Alibaba1688ImageUploadClient imageUploadClient;

    /**
     * Preview the official image search. Provide {@code imageUrl} (publicly reachable) and/or {@code imageId}
     * (from {@code /upload}); {@code keyword}/{@code aux} are the optional correction terms.
     */
    @GetMapping("/search")
    public OfferImageSearchResultVO search(@RequestParam(required = false) String imageUrl,
                                           @RequestParam(required = false) String imageId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String aux,
                                           @RequestParam(required = false, defaultValue = "en") String country,
                                           @RequestParam(required = false, defaultValue = "1") Integer page,
                                           @RequestParam(required = false, defaultValue = "5") Integer size) {
        return imageSearchClient.searchByImage(imageUrl, imageId, keyword, aux, country, page, size);
    }

    /** Upload an image (by url) to 1688 and return its {@code imageId} for reuse by {@code /search}. */
    @PostMapping("/upload")
    public ImageUploadResultVO upload(@RequestParam String imageUrl) {
        return imageUploadClient.uploadByUrl(imageUrl);
    }
}
