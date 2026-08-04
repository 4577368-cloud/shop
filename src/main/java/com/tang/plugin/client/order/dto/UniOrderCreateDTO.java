package com.tang.plugin.client.order.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class UniOrderCreateDTO {
    private String outerOrderNo;
    private String orderLanguage;
    private String comment;
    private String destination;
    private String currency;
    private String lang;
    private String device;
    private String dataSource;
    private String storeSource;
    private String storeId;
    private String shopName;
    private String shopUrl;
    private BigDecimal totalAmount;
    private UniOrderAddressDTO address;
    private List<UniOrderItemCreateDTO> orderItems = new ArrayList<>();
}
