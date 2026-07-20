package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ProductLogisticsProfileVO {
    private String thirdPlatformItemId;
    private String title;
    private String logisticsType;
    private String logisticsTypeLabel;
    private double confidence;
    private List<String> signals = new ArrayList<>();
    private String classifySource;
    private boolean reviewed;
}
