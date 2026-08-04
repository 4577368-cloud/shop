package com.tang.plugin.client.order.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class UniOrderItemCreateDTO {
    private String dataSource;
    private String orderLanguage;
    private BigDecimal actPrice;
    private String goodsAttribute;
    private String goodsId;
    private String goodsImg;
    private String goodsName;
    private String goodsUrl;
    private String comment;
    private Integer saleType;
    private Integer showWay;
    private Long pluginOrderLineId;
    private Long pluginOrderPurchaseLineId;
    private String skuId;
    private BigDecimal unitPrice;
    private Integer nums;
    private BigDecimal discountAmount;
    private Integer goodsType;
    private Boolean useStockFlag;
}
