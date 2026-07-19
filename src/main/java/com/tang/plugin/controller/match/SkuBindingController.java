package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.service.match.SkuBindingOverviewService;
import com.tang.plugin.service.match.sku.Crossborder1688ProductClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SKU binding endpoints (read-only in S1-a/S1-b0). Lists bound products expanded per variant, and
 * (S1-b0) previews a 1688 offer's SKU matrix via the cross-border detail API. Public under
 * {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match/sku")
public class SkuBindingController {

    @Resource
    private SkuBindingOverviewService skuBindingOverviewService;
    @Resource
    private Crossborder1688ProductClient crossborder1688ProductClient;

    @GetMapping("/overview")
    public List<SkuProductOverviewVO> overview(@RequestParam String shopName) {
        return skuBindingOverviewService.overview(shopName);
    }

    /**
     * S1-b0 preview: fetch a 1688 offer's normalized SKU matrix. Read-only; used to verify the
     * cross-border AOP integration before S1-b1 auto-alignment. No persistence.
     */
    @GetMapping("/offer-detail")
    public OfferDetailVO offerDetail(@RequestParam String offerId,
                                     @RequestParam(required = false, defaultValue = "en") String country) {
        return crossborder1688ProductClient.queryProductDetail(offerId, country);
    }
}
