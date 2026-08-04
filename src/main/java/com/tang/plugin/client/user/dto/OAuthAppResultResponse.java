package com.tang.plugin.client.user.dto;

import lombok.Data;

@Data
public class OAuthAppResultResponse {
    private boolean bind;
    private boolean mailRegistered;
    private String userName;
    private String avatar;
    private String account;
    private Oauth2TokenResponse tokenInfo;
}
