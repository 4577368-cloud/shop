package com.tang.plugin.domain.dto.webhook;

import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.webhook.ProductWebHookEventEnum;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ProductWebHookDTO {
    private String shopName;
    private Long shopId;
    private PluginType shopType;
    private ProductWebHookEventEnum event;
    private String platformProductJson;
}
