package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ShopComboSaveVO {
    private String productId;
    private String kind;
    private boolean saved;
    /** Checkout Function not live yet — config is stored for later apply. */
    private boolean checkoutPending;
    private String message;
}
