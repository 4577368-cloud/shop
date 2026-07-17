package com.tang.plugin.domain.entity.user;

import com.tang.plugin.enums.PluginType;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class UserAuthShop {
    private Long shopId;
    private String shopName;
    private PluginType shopType;
    private String accessToken;
    private String refreshToken;
    private String currency;
    private Integer status;
    private Integer delFlag;
    private Instant authorizedAt;
}
