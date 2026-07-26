package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.ConfirmImageMatchDTO;
import com.tang.plugin.domain.dto.match.ImageBindingView;
import com.tang.plugin.domain.dto.match.image.ImageSearchRequest;
import com.tang.plugin.domain.dto.match.image.ImageSearchResultVO;
import com.tang.plugin.service.match.image.ImageBindingSnapshotBackfillService;
import com.tang.plugin.service.match.image.ImageBindingSnapshotBackfillService.BackfillResult;
import com.tang.plugin.service.match.image.ImageMatchConfirmService;
import com.tang.plugin.service.match.image.ImageSearchService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
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
    @Resource
    private ImageBindingSnapshotBackfillService imageBindingSnapshotBackfillService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/image-search")
    public ImageSearchResultVO imageSearch(HttpServletRequest httpRequest,
                                          @RequestBody ImageSearchRequest request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), request.getShopName());
        return imageSearchService.searchByShopProduct(
                request.getShopName(),
                request.getThirdPlatformItemId(),
                request.getLimit(),
                request.getSearchImageUrl(),
                request.getCountry());
    }

    @PostMapping("/image-search/confirm")
    public ImageBindingView confirm(HttpServletRequest httpRequest,
                                    @RequestBody ConfirmImageMatchDTO request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), request.getShopName());
        return imageMatchConfirmService.confirm(request);
    }

    @GetMapping("/image-search/bindings")
    public List<ImageBindingView> bindings(HttpServletRequest request,
                                           @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return imageMatchConfirmService.listActiveBindings(shopName);
    }

    /** "确认无误": promote a product's PENDING (AI-suggested) image binding to ACTIVE. */
    @PostMapping("/image-search/ack")
    public void ack(HttpServletRequest request,
                   @RequestParam String shopName, @RequestParam String thirdPlatformItemId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        imageMatchConfirmService.acknowledge(shopName, thirdPlatformItemId);
    }

    /** "取消关联": soft-unbind a product's image binding (PENDING or ACTIVE). */
    @PostMapping("/image-search/unbind")
    public void unbind(HttpServletRequest request,
                       @RequestParam String shopName, @RequestParam String thirdPlatformItemId) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        imageMatchConfirmService.unbind(shopName, thirdPlatformItemId);
    }

    /**
     * Repair legacy bindings whose {@code match_reason} lacks the image/price snapshot (re-search →
     * match the bound offer → else derive from offer detail). One-shot, idempotent, fail-open.
     */
    @PostMapping("/image-search/backfill-snapshots")
    public BackfillResult backfillSnapshots(HttpServletRequest request,
                                            @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return imageBindingSnapshotBackfillService.backfill(shopName);
    }
}
