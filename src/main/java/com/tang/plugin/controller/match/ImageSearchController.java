package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.ImageBindingView;
import com.tang.plugin.domain.dto.match.image.ImageSearchRequest;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.service.match.image.ImageMatchConfirmService;
import com.tang.plugin.service.match.image.ImageSearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 1688 image-search matching endpoints (path A).
 *
 * <ul>
 *   <li><b>A3-2a</b> {@code POST /image-search}: stateless preview — the backend decides the search image
 *       + correction query (original → title → LLM, with graceful degradation) and returns candidates.
 *       Read-only, no persistence.</li>
 *   <li><b>A3-2b</b> {@code POST /image-search/confirm}: confirm a chosen offer into a SKU-level binding
 *       (route B: default variant resolved from the local SKU mirror). {@code GET /image-search/bindings}
 *       lists the shop's ACTIVE image bindings for回显.</li>
 * </ul>
 *
 * Public under {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match")
public class ImageSearchController {

    @Resource
    private ImageSearchService imageSearchService;
    @Resource
    private ImageMatchConfirmService imageMatchConfirmService;

    @PostMapping("/image-search")
    public ImageSearchResultVO imageSearch(@RequestBody ImageSearchRequest request) {
        return imageSearchService.searchByShopProduct(
                request.getShopName(),
                request.getThirdPlatformItemId(),
                request.getLimit());
    }

    @PostMapping("/image-search/confirm")
    public ImageBindingView confirm(@RequestBody ConfirmImageMatchDTO request) {
        return imageMatchConfirmService.confirm(request);
    }

    @GetMapping("/image-search/bindings")
    public List<ImageBindingView> bindings(@RequestParam String shopName) {
        return imageMatchConfirmService.listActiveBindings(shopName);
    }
}
