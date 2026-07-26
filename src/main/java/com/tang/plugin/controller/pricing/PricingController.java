package com.tang.plugin.controller.pricing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.pricing.PricingTemplateUpsertRequest;
import com.tang.plugin.domain.dto.pricing.PricingTemplateVO;
import com.tang.plugin.service.pricing.PricingTemplateService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-shop pricing template management (M1-2). Public endpoint under /api/plugin/**; deterministic
 * rule only, no Shopify calls. GET returns the effective template (stored or system default);
 * POST upserts the shop's single active template.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/pricing")
public class PricingController {

    @Resource
    private PricingTemplateService pricingTemplateService;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @GetMapping("/template")
    public PricingTemplateVO getTemplate(HttpServletRequest request,
                                          @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("template requires shopName");
        }
        return pricingTemplateService.toVO(pricingTemplateService.getEffective(shopName));
    }

    @PostMapping("/template")
    public PricingTemplateVO upsertTemplate(HttpServletRequest httpRequest,
                                            @RequestBody PricingTemplateUpsertRequest request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), request.getShopName());
        return pricingTemplateService.toVO(pricingTemplateService.upsert(request));
    }

    @DeleteMapping("/template")
    public PricingTemplateVO clearTemplate(HttpServletRequest request,
                                            @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("template requires shopName");
        }
        return pricingTemplateService.toVO(pricingTemplateService.clear(shopName));
    }
}
