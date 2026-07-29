package com.tang.plugin.domain.query.order;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrderPackageChoosedContentReq {
    private String couponId = "";
    private String passwordDiscount = "";
    private List<String> incrementList = new ArrayList<>();
    private Integer insure = 0;
    private Integer useInsure = 0;
    private String currency = "USD";
    private OrderPackageQueryFormReq queryForm;
}
