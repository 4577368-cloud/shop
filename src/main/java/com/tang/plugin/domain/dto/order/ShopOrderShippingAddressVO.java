package com.tang.plugin.domain.dto.order;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Shopify shipping / recipient snapshot for order-center recipient panel.
 * Populated from {@code t_draft_order_address}; merchants may supplement incomplete fields.
 */
@Data
@Accessors(chain = true)
public class ShopOrderShippingAddressVO {
    private Long draftOrderId;
    private String email;
    private String firstName;
    private String lastName;
    private String name;
    private String company;
    private String phone;
    private String address1;
    private String address2;
    private String city;
    private String province;
    private String zip;
    private String country;
    private String countryCode;
    /** True when any intl-required field is blank (name, address1, city, countryCode, phone). */
    private Boolean incomplete;
}
