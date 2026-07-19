package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.dto.match.image.ImageSearchRequest;
import com.tang.plugin.service.match.image.ImageSearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A3-1 stateless 1688 image-search preview. Read-only: takes a mirrored shop product's primary image,
 * returns normalized 1688 candidates (top-1 first). No persistence, no binding (that is A3-2).
 * Public under {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match")
public class ImageSearchController {

    @Resource
    private ImageSearchService imageSearchService;

    @PostMapping("/image-search")
    public List<ImageSearchProductVO> imageSearch(@RequestBody ImageSearchRequest request) {
        return imageSearchService.searchByShopProduct(
                request.getShopName(),
                request.getThirdPlatformItemId(),
                request.getLimit(),
                request.getQuery());
    }
}
