package com.tang.plugin.domain.dto.publish;

import lombok.Data;

/** One Shopify product option value (e.g. 颜色 = 黑色). */
@Data
public class PublishOptionValue {
    private String optionName;
    private String value;
}
