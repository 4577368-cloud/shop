package com.tang.plugin.domain.entity.product;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Minimal product media mirror (P1): no variant-media binding.
 */
@Data
@Accessors(chain = true)
public class ThirdPlatformProductMedia {
    private Long id;
    private String shopName;
    private String shopType;
    private String thirdPlatformItemId;
    private String mediaId;
    private String url;
    private String alt;
    private Integer position;
    private Integer delFlag;
}
