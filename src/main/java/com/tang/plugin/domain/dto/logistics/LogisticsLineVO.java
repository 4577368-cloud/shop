package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 物流线路视图对象。对应前端 {@code LogisticsLine} 类型。
 *
 * <p>字段命名与前端完全一致（camelCase），由 fastjson2 序列化为 JSON 后
 * 前端可直接消费，无需字段映射。
 */
@Data
@Accessors(chain = true)
public class LogisticsLineVO {
    private String lineCode;
    private String lineName;
    private Double estimatedFee;
    private String currency;
    private Integer estimatedDays;
    private String transitTimeLabel;
    private String carrier;
    private Boolean supportsBattery;
    private Boolean trackingAvailable;
    private Integer priority;
}
