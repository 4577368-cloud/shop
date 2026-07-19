package com.tang.plugin.controller.match;

import com.tang.plugin.domain.dto.match.SkuProductOverviewVO;
import com.tang.plugin.service.match.SkuBindingOverviewService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * S1-a SKU binding overview (read-only). Lists shop products that have at least one ACTIVE binding,
 * aggregated per product and expanded into Shopify variants with their current binding state.
 * Public under {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/match/sku")
public class SkuBindingController {

    @Resource
    private SkuBindingOverviewService skuBindingOverviewService;

    @GetMapping("/overview")
    public List<SkuProductOverviewVO> overview(@RequestParam String shopName) {
        return skuBindingOverviewService.overview(shopName);
    }
}
