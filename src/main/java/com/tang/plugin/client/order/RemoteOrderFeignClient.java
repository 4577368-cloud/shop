package com.tang.plugin.client.order;

import com.tang.common.core.domain.R;
import com.tang.plugin.client.order.dto.UniOrderCreateBodyDTO;
import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign mirror of tang-api-order RemoteOrderService#uniOrder.
 * Activated only when tang.plugin.remote.order.enabled=true;
 * set tang.plugin.remote.order.url to the order gateway base.
 */
@ConditionalOnProperty(prefix = "tang.plugin.remote.order", name = "enabled", havingValue = "true")
@FeignClient(
        contextId = "sourcePluginRemoteOrder",
        name = "tang-order",
        url = "${tang.plugin.remote.order.url}"
)
public interface RemoteOrderFeignClient {

    @PostMapping({"/remote/order/uniOrder", "/uniOrder"})
    R<UniOrderCreateResDTO> uniOrder(@RequestBody UniOrderCreateBodyDTO req);
}
