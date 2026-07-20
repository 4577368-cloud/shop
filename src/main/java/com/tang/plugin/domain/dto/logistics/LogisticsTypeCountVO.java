package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LogisticsTypeCountVO {
    private String type;
    private String label;
    private int count;
}
