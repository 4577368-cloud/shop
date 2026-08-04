package com.tang.plugin.client.user.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class OAuthAppLoginRequest {
    private String platform;
    private String openId;
    private String language;
    private String email;
    private String deviceCode;
    private String device;
    private String identifier;
    private String userName;
    private String token;
}
