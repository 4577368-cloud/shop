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
    private List<ShopOrderLineItemVO> lineItems = new ArrayList<>();
}
