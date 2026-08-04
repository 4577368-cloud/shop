package com.tang.plugin.sdk.pay;

import com.tang.common.core.domain.R;
import com.tang.common.core.exception.CustomException;
import com.tang.common.core.web.domain.AjaxResult;
import com.tang.plugin.client.pay.RemotePayFeignClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bridge to tang-pay. When remote pay is disabled, returns stub channels for local UI wiring.
 */
@Slf4j
@Component
public class RemotePaySdkClient {

    @Value("${tang.plugin.remote.pay.enabled:false}")
    private boolean remoteEnabled;

    @Resource
    private ObjectProvider<RemotePayFeignClient> remotePayFeignClient;

    public AjaxResult channelList(String orderNo, String country, boolean excludeBalance) {
        if (remoteEnabled) {
            RemotePayFeignClient feign = requireFeign();
            return feign.channelList(orderNo, country, excludeBalance);
        }
        log.info("RemotePaySdkClient stub channelList orderNo={}", orderNo);
        return AjaxResult.success(List.of(
                Map.of("channel", "balance", "name", "余额支付"),
                Map.of("channel", "paypal", "name", "PayPal"),
                Map.of("channel", "alipaycard", "name", "信用卡")
        ));
    }

    public R<?> paymentOrder(String data) {
        if (remoteEnabled) {
            return requireFeign().paymentOrder(data);
        }
        log.info("RemotePaySdkClient stub paymentOrder dataLen={}", data == null ? 0 : data.length());
        return R.ok(Map.of("status", "pending", "stub", true));
    }

    private RemotePayFeignClient requireFeign() {
        RemotePayFeignClient feign = remotePayFeignClient.getIfAvailable();
        if (feign == null) {
            throw new CustomException(
                    "Remote pay enabled but Feign client missing; set tang.plugin.remote.pay.url");
        }
        return feign;
    }
}
