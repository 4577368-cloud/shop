package com.tang.plugin.domain.query.order;

import lombok.Data;

@Data
public class OrderPackageQueryFormReq {
    private Long currencyId;
    private Integer declareMode = 0;
    private Integer registrationType = 0;
    private Integer tax = 0;
}
