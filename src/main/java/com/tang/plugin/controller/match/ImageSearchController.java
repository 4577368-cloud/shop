package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.image.ImageSearchRequest;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.service.match.image.ImageSearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A3-2a stateless 1688 image-search preview. Read-only: takes a mirrored shop product, and the backend
 * alone decides the search image + correction query (original image → title query → LLM query, with
 * graceful degradation). Returns the candidates plus how they were resolved. No persistence, no binding
 * (that is A3-2b). Public under {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match")
public class ImageSearchController {

    @Resource
    private ImageSearchService imageSearchService;

    @PostMapping("/image-search")
    public ImageSearchResultVO imageSearch(@RequestBody ImageSearchRequest request) {
        return imageSearchService.searchByShopProduct(
                request.getShopName(),
                request.getThirdPlatformItemId(),
                request.getLimit());
    }
}
