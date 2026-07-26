package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 积分加购包（§3）。Table: credit_packages
 */
@Data
@Accessors(chain = true)
public class CreditPackage {
    private Long id;
    private String code;
    private String name;
    private Long priceUsdCents;
    private Integer creditsNormal;
    private Integer creditsPromo;
    private Instant promoUntil;
    private Integer durationDays;
    private Integer sortOrder;
    private Boolean active;
}
