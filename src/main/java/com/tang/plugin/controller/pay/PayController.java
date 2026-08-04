package com.tang.plugin.controller.pay;

import com.tang.common.core.domain.R;
import com.tang.common.core.web.domain.AjaxResult;
import com.tang.plugin.sdk.pay.RemotePaySdkClient;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tangbuy pay BFF — mirrors reference PayController under /api/plugin/pay for JWT auth.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/pay")
public class PayController {

    @Resource
    private RemotePaySdkClient remotePaySdkClient;

    @GetMapping("/channelList")
    public AjaxResult channelList(
            HttpServletRequest request,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "excludeBalance", required = false, defaultValue = "false")
            boolean excludeBalance) {
        Long userId = (Long) request.getAttribute("userId");
        log.info("channelList userId={} orderNo={}", userId, orderNo);
        return remotePaySdkClient.channelList(orderNo, country, excludeBalance);
    }

    @PostMapping("/payment/order")
    public R<?> paymentOrder(
            HttpServletRequest request,
            @RequestBody String data,
            @RequestHeader(value = "X-Ga-Client-Id", defaultValue = "") String xGaClientId,
            @RequestHeader(value = "X-Ga-Session-Id", defaultValue = "") String xGaSessionId) {
        Long userId = (Long) request.getAttribute("userId");
        log.info("paymentOrder userId={} gaClient={} gaSession={}", userId, xGaClientId, xGaSessionId);
        return remotePaySdkClient.paymentOrder(data);
    }
}
