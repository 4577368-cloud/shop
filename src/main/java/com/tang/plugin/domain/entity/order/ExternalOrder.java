package com.tang.plugin.domain.entity.order;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal standard external order — platform JSON must be adapted into this.
 */
@Data
@Accessors(chain = true)
public class ExternalOrder {
    /** Platform outer order id */
    private String orderId;
    private String orderName;
    private String financialStatus;
    private String fulfillmentStatus;
    private String currency;
    private BigDecimal totalPrice;
    private String email;
    private String phone;
    private String countryCode;
    private Long countryId;
    private String provinceCode;
    private String city;
    private String address1;
    private String address2;
    private String zip;
    private Instant createdAt;
    private Instant updatedAt;
    private Boolean cancelled;
    private Boolean voided;
    private Boolean fullyRefunded;
    private List<ExternalOrderLine> lines = new ArrayList<>();
}
