package com.tang.plugin.client.user.dto;

import lombok.Data;

@Data
public class Oauth2TokenResponse {
    private String token;
    private String refreshToken;
    private String tokenHead;
    private int expiresIn;
    private boolean authFlag;
    private String account;
    private boolean riskLogin;
    private String uuid;
}
