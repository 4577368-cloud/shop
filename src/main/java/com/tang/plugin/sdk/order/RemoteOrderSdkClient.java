package com.tang.plugin.sdk.order;

import com.tang.plugin.domain.dto.order.UniOrderCreateResDTO;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import com.tang.plugin.domain.entity.order.TOrderLinePurchaseDO;
import com.tang.plugin.domain.query.order.DraftOrderPackageCreateReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Feign/HTTP bridge to tang-order RemoteOrderService#uniOrder.
 * When tang.plugin.remote.order.enabled=false (default), returns a local stub tradeNo
 * so dropship purchase can be exercised end-to-end without company services.
 */
@Slf4j
@Component
public class RemoteOrderSdkClient {

    @Value("${tang.plugin.remote.order.enabled:false}")
    private boolean remoteEnabled;

    @Value("${tang.plugin.remote.order.base-url:}")
    private String baseUrl;

    public UniOrderCreateResDTO uniOrder(TDraftOrderDO order,
                                         List<TDraftOrderLineDO> lines,
                                         List<TOrderLinePurchaseDO> purchases,
                                         DraftOrderPackageCreateReq packageCreateInfo) {
        if (remoteEnabled) {
            // Placeholder: wire Feign RemoteOrderService when tang-api-order is available.
            log.warn("Remote uniOrder enabled but Feign client not yet bound; falling back to stub. baseUrl={}",
                    baseUrl);
        }
        return stub(order, purchases);
    }

    private UniOrderCreateResDTO stub(TDraftOrderDO order, List<TOrderLinePurchaseDO> purchases) {
        String tradeNo = "PAY" + System.currentTimeMillis();
        String orderNo = "TO" + Instant.now().getEpochSecond();
        Map<String, String> map = new HashMap<>();
        int i = 1;
        BigDecimal total = BigDecimal.ZERO;
        for (TOrderLinePurchaseDO p : purchases) {
            String itemNo = "TI" + Instant.now().getEpochSecond() + String.format("%02d", i++);
            map.put(String.valueOf(p.getId()), itemNo);
            if (p.getPurchaseAmount() != null) {
                total = total.add(p.getPurchaseAmount());
            }
        }
        log.info("RemoteOrderSdkClient stub uniOrder draftId={} tradeNo={} items={}",
                order.getId(), tradeNo, map.size());
        return new UniOrderCreateResDTO()
                .setTradeNo(tradeNo)
                .setOrderNo(orderNo)
                .setType("dropship")
                .setExpireTime(Instant.now().plus(30, ChronoUnit.MINUTES))
                .setTotalAmount(total)
                .setOrderNoMap(map);
    }
}
