package com.tang.plugin.controller.pricing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.pricing.PricingTemplateUpsertRequest;
import com.tang.plugin.domain.dto.pricing.PricingTemplateVO;
import com.tang.plugin.service.pricing.PricingTemplateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

    @GetMapping("/template")
    public PricingTemplateVO getTemplate(@RequestParam("shopName") String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("template requires shopName");
        }
        return pricingTemplateService.toVO(pricingTemplateService.getEffective(shopName));
    }

    @PostMapping("/template")
    public PricingTemplateVO upsertTemplate(@RequestBody PricingTemplateUpsertRequest request) {
        return pricingTemplateService.toVO(pricingTemplateService.upsert(request));
    }
}
