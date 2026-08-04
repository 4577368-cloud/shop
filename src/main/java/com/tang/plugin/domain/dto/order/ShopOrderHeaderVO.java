package com.tang.plugin.domain.dto.order;

import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class ShopOrderHeaderVO extends ThirdPlatformOrder {
    private Long draftOrderId;
    /** DraftOrderItemEnum code when draft exists. */
    private Integer draftStatus;
    /** FE tab key from OrderStatusMapper (pendingOrder / pendingPayment / …). */
    private String orderStatus;
    private String tradeNo;
    private String tangbuyOrderNo;
    private String exceptionTag;
    /** Optional Admin goodsStatus fine-grain. */
    private Integer goodsStatus;
    private List<ShopOrderLineItemVO> lineItems = new ArrayList<>();
    /** Recipient / shipping address — omit from list UI; used by detail recipient panel. */
    private ShopOrderShippingAddressVO shippingAddress;
}
