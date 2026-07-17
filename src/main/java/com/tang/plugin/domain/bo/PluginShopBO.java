package com.tang.plugin.domain.bo;

import com.tang.plugin.enums.PluginType;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PluginShopBO {
    private Long shopId;
    private String shopName;
    private PluginType shopType;
    private String currency;
}
