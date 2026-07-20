package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LogisticsAnalysisVO {
    private String shopName;
    private String status;
    private int analyzedCount;
    private int skippedUnboundCount;
    private List<LogisticsTypeCountVO> distribution = new ArrayList<>();
    private List<String> highRiskTypes = new ArrayList<>();
    private List<ProductLogisticsProfileVO> profiles = new ArrayList<>();
}
