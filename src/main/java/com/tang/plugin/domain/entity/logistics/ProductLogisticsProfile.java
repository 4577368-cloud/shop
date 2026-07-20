package com.tang.plugin.domain.entity.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class ProductLogisticsProfile {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String titleSnapshot;
    private String logisticsType;
    private Double confidence;
    private String signalsJson;
    private String classifySource;
    private Integer reviewed;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
