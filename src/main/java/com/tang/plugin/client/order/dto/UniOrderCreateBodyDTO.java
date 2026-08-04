package com.tang.plugin.client.order.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Lite mirror of tang-api-order UniOrderCreateBodyDTO for Feign until the private jar is on classpath.
 */
@Data
@Accessors(chain = true)
public class UniOrderCreateBodyDTO {
    private List<Long> pluginOrderIds = new ArrayList<>();
    private List<UniOrderCreateDTO> orders = new ArrayList<>();
    private BigDecimal packageAmountPre;
    private String packageBzNo;
    private String lang;
    private String currency;
    private String device;
    private Long inquiryId;
    private String pluginType;
    private Integer saleType;
    private Boolean materialFlag;
}
