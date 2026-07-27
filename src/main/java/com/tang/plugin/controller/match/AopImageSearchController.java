package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.image.ImageUploadResultVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import com.tang.plugin.service.match.image.Alibaba1688ImageSearchClient;
import com.tang.plugin.service.match.image.Alibaba1688ImageUploadClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
     *
     * <p>Non-alicdn URLs (e.g. TikTok CDN on the ranking board) are uploaded first to obtain an
     * {@code imageId} — 1688 cannot fetch those hosts as {@code imageAddress}.
     */
    @GetMapping("/search")
    public OfferImageSearchResultVO search(@RequestParam(required = false) String imageUrl,
                                           @RequestParam(required = false) String imageId,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String aux,
                                           @RequestParam(required = false, defaultValue = "en") String country,
                                           @RequestParam(required = false, defaultValue = "1") Integer page,
                                           @RequestParam(required = false, defaultValue = "5") Integer size) {
        String resolvedAddress = imageUrl;
        String resolvedId = imageId;
        if (StringUtils.isBlank(resolvedId) && StringUtils.isNotBlank(imageUrl) && !isAlicdn(imageUrl)) {
            resolvedId = imageUploadClient.uploadByUrl(imageUrl).getImageId();
            resolvedAddress = null;
            log.info("image-aop search: uploaded non-alicdn url → imageId={}", resolvedId);
        }
        return imageSearchClient.searchByImage(resolvedAddress, resolvedId, keyword, aux, country, page, size);
    }

    /** Upload an image (by url) to 1688 and return its {@code imageId} for reuse by {@code /search}. */
    @PostMapping("/upload")
    public ImageUploadResultVO upload(@RequestParam String imageUrl) {
        return imageUploadClient.uploadByUrl(imageUrl);
    }

    private static boolean isAlicdn(String url) {
        return StringUtils.containsIgnoreCase(url, "alicdn.com");
    }
}
