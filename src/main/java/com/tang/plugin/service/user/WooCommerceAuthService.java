package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.WooCommerceStoreAuth;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.repository.WooCommerceStoreAuthRepository;
import com.tang.plugin.service.auth.JwtService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class WooCommerceAuthService {

    @Value("${tang.plugin.woocommerce.callback-url:http://localhost:8088/api/plugin/woocommerce/auth/callback}")
    private String callbackUrl;

    @Value("${tang.plugin.woocommerce.app-name:TangBuy}")
    private String appName;

    @Resource
    private JwtService jwtService;
    @Resource
    private WooCommerceStoreAuthRepository wooCommerceStoreAuthRepository;
    @Resource
    private UserShopRepository userShopRepository;

    public String buildAuthorizeUrl(String rawDomain, Long userId, String userName) {
        if (userId == null) {
            throw new CustomException("User not authenticated", 401, "UNAUTHENTICATED");
        }
        String domain = normalizeDomain(rawDomain);
        String pluginToken = jwtService.generatePluginToken(
                userId,
                StringUtils.defaultString(userName),
                PluginType.WOOCOMMERCE.getCode(),
                domain,
                null,
                600L);
        String authUrl = "https://" + domain + "/wc-auth/v1/authorize";
        String returnUrl = "https://" + domain + "/wp-admin/admin.php?page=tangbuy&user_id="
                + urlEncode(pluginToken);
        String cb = StringUtils.trimToEmpty(callbackUrl);
        if (!cb.contains("?")) {
            cb += "?domain=" + urlEncode(domain);
        } else {
            cb += "&domain=" + urlEncode(domain);
        }
        return authUrl
                + "?app_name=" + urlEncode(appName)
                + "&scope=read_write"
                + "&user_id=" + urlEncode(pluginToken)
                + "&return_url=" + urlEncode(returnUrl)
                + "&callback_url=" + urlEncode(cb);
    }

    @Transactional
    public void handleCallback(String rawDomain, String jsonBody) {
        String domain = normalizeDomain(rawDomain);
        JSONObject body = JSONObject.parseObject(StringUtils.defaultString(jsonBody, "{}"));
        String token = body.getString("user_id");
        JwtService.PluginTokenClaims claims = jwtService.parsePluginToken(token);
        if (claims == null || claims.userId() == null) {
            throw new CustomException("Invalid WooCommerce plugin token", 400, "INVALID_PLUGIN_TOKEN");
        }
        String consumerKey = body.getString("consumer_key");
        String consumerSecret = body.getString("consumer_secret");
        if (StringUtils.isAnyBlank(consumerKey, consumerSecret)) {
            throw new CustomException("Invalid OAuth credentials", 400, "INVALID_WOO_CREDENTIALS");
        }
        WooCommerceStoreAuth auth = new WooCommerceStoreAuth()
                .setUserId(claims.userId())
                .setShopName(domain)
                .setSiteUrl("https://" + domain)
                .setConsumerKey(consumerKey)
                .setConsumerSecret(consumerSecret)
                .setKeyPermissions(body.getString("key_permissions"))
                .setKeyId(body.getString("key_id"))
                .setRemark("通过WordPress插件授权连接");
        Long authId = wooCommerceStoreAuthRepository.upsertActive(auth);
        userShopRepository.upsertBinding(claims.userId(), domain, domain);
        log.info("WooCommerce auth saved domain={} userId={} authId={}", domain, claims.userId(), authId);
    }

    private static String normalizeDomain(String rawDomain) {
        String domain = StringUtils.trimToEmpty(rawDomain)
                .replaceFirst("^https?://", "")
                .replaceAll("[:/?#].*$", "")
                .replaceAll("/$", "")
                .toLowerCase();
        if (StringUtils.isBlank(domain)) {
            throw new CustomException("domain is required", 400, "INVALID_DOMAIN");
        }
        return domain;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
