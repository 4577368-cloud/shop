package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ShopGiftSaveVO {
    private String productId;
    private String kind;
    private boolean saved;
    /** Free gift at checkout not applied yet — rule is stored for later Function. */
    private boolean checkoutPending;
    private String message;
}
