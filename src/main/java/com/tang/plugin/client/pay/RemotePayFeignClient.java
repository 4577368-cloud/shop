package com.tang.plugin.client.pay;

import com.tang.common.core.domain.R;
import com.tang.common.core.web.domain.AjaxResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Feign mirror of tang-api-pay RemotePayService.
 * Activated when tang.plugin.remote.pay.enabled=true.
 */
@ConditionalOnProperty(prefix = "tang.plugin.remote.pay", name = "enabled", havingValue = "true")
@FeignClient(
        contextId = "sourcePluginRemotePay",
        name = "tang-pay",
        url = "${tang.plugin.remote.pay.url}"
)
public interface RemotePayFeignClient {

    @GetMapping({"/remote/pay/channelList", "/channelList"})
    AjaxResult channelList(
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "country", required = false) String country,
            @RequestParam(value = "excludeBalance", required = false, defaultValue = "false") boolean excludeBalance);

    @PostMapping({"/remote/pay/payment/order", "/payment/order"})
    R<?> paymentOrder(@RequestBody String data);
}
