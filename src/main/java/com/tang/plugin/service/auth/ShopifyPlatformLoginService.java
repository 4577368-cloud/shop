package com.tang.plugin.service.auth;

import com.tang.common.core.constant.Constants;
import com.tang.common.core.domain.R;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.client.user.RemoteUserPlatformClient;
import com.tang.plugin.client.user.dto.OAuthAppLoginRequest;
import com.tang.plugin.client.user.dto.OAuthAppResultResponse;
import com.tang.plugin.client.user.dto.Oauth2TokenResponse;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class ShopifyPlatformLoginService {

    @Resource
    private RemoteUserPlatformClient remoteUserPlatformClient;

    @Value("${tang.plugin.platform.shopify-login-token:tang-source-plugin-shopify}")
    private String internalToken;

    public Oauth2TokenResponse login(String shopName, String email, String displayName) {
        OAuthAppLoginRequest request = new OAuthAppLoginRequest()
                .setPlatform("SHOPIFY")
                .setOpenId(shopName)
                .setEmail(email)
                .setUserName(StringUtils.defaultIfBlank(displayName, shopName))
                .setLanguage("en")
                .setDevice("pc")
                .setDeviceCode("shopify:" + shopName)
                .setIdentifier("shopify:" + shopName)
                .setToken(internalToken);

        R<OAuthAppResultResponse> response = remoteUserPlatformClient.login(defaultHeaders(), request);
        OAuthAppResultResponse data = response == null ? null : response.getData();
        Oauth2TokenResponse token = data == null ? null : data.getTokenInfo();
        if (response == null || response.getCode() != Constants.SUCCESS || token == null
                || StringUtils.isBlank(token.getToken())) {
            throw new CustomException(response == null ? "Shopify platform login failed" : response.getMsg(),
                    401, "PLATFORM_LOGIN_FAILED");
        }
        return token;
    }

    private HttpHeaders defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("device", "pc");
        headers.add("lang", "cn");
        headers.add("currency", "CNY");
        headers.add("tang-request-device", "web");
        headers.add("tang-request-render", "csr");
        headers.add("tang-request-rewrite", "true");
        headers.add("x-timezone", "8");
        headers.add("x-timezone-id", "Asia/Shanghai");
        return headers;
    }
}
