package com.tang.plugin.controller.logistics;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.logistics.CorrectLogisticsTypeRequest;
import com.tang.plugin.domain.dto.logistics.LogisticsAnalysisVO;
import com.tang.plugin.domain.dto.logistics.LogisticsTemplateUpsertRequest;
import com.tang.plugin.domain.dto.logistics.LogisticsTemplateVO;
import com.tang.plugin.domain.dto.logistics.ProductLogisticsProfileVO;
import com.tang.plugin.service.catalog.TangbuyMallClient;
import com.tang.plugin.service.logistics.LogisticsAnalysisService;
import com.tang.plugin.service.logistics.LogisticsTemplateService;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logistics Phase 1: product type analysis + shop logistics strategy template. No lane matching yet.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/logistics")
public class LogisticsController {

    @Resource
    private LogisticsAnalysisService logisticsAnalysisService;
    @Resource
    private LogisticsTemplateService logisticsTemplateService;
    @Resource
    private TangbuyMallClient tangbuyMallClient;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    /** Classify (or refresh) bound products and return the distribution summary. */
    @PostMapping("/analyze")
    public LogisticsAnalysisVO analyze(
            HttpServletRequest request,
            @RequestParam("shopName") String shopName,
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("analyze requires shopName");
        }
        return logisticsAnalysisService.analyze(shopName, force);
    }

    /** Read the last analysis snapshot without reclassifying USER rows (re-runs auto for others). */
    @GetMapping("/analysis")
    public LogisticsAnalysisVO analysis(HttpServletRequest request,
                                        @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("analysis requires shopName");
        }
        return logisticsAnalysisService.analyze(shopName, false);
    }

    @PostMapping("/correct-type")
    public ProductLogisticsProfileVO correctType(HttpServletRequest httpRequest,
                                                  @RequestBody CorrectLogisticsTypeRequest request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), request.getShopName());
        return logisticsAnalysisService.correct(request);
    }

    @GetMapping("/template")
    public LogisticsTemplateVO getTemplate(HttpServletRequest request,
                                           @RequestParam("shopName") String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return logisticsTemplateService.getEffective(shopName);
    }

    @PostMapping("/template")
    public LogisticsTemplateVO upsertTemplate(HttpServletRequest httpRequest,
                                              @RequestBody LogisticsTemplateUpsertRequest request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), request.getShopName());
        return logisticsTemplateService.upsert(request);
    }

    /**
     * Proxy Tangbuy {@code estimateSkuSaleFeePrice} using server-side mall token
     * ({@code TANG_PLUGIN_TANGBUY_MALL_TOKEN} on Render).
     */
    @PostMapping("/estimate")
    public Object estimate(@RequestBody String body) {
        if (StringUtils.isBlank(body)) {
            throw new CustomException("estimate requires JSON body");
        }
        String raw = tangbuyMallClient.estimateSkuSaleFeePrice(body);
        if (StringUtils.isBlank(raw)) {
            throw new CustomException("Tangbuy logistic estimate empty response");
        }
        return JSON.parse(raw);
    }
}
