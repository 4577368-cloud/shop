package com.tang.plugin.enums.order;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PluginOrderTypeEnum {
    EXTERNAL_PULL(1, "外部拉取（SHOPIFY）代发订单"),
    DIRECT_STOCK(2, "直购备货订单"),
    DIRECT_SHIP(3, "直购直发订单"),
    INQUIRY_STOCK(4, "询盘备货订单"),
    INQUIRY_SHIP(5, "询盘直发订单"),
    INQUIRY_MATERIAL(6, "询盘物料订单");

    @EnumValue
    private final Integer code;
    private final String desc;

    public static PluginOrderTypeEnum ofCode(Integer code) {
        if (code == null) return null;
        for (PluginOrderTypeEnum e : values()) {
            if (e.code.equals(code)) return e;
        }
        return null;
    }
}
