package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.service.auth.PlatformTokenService;
import com.tang.plugin.service.auth.ShopifyPlatformLoginService;
import com.tang.plugin.client.user.dto.Oauth2TokenResponse;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import com.tang.plugin.service.order.external.component.ShopifyAuthComponent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exchange a Shopify App Bridge session token for a Tangbuy access JWT (embedded Admin iframe).
 *
 * <p>Flow:
 * <ol>
 *   <li>Verify Shopify session JWT (API secret + aud = API key)</li>
 *   <li>If {@code user_shop} exists → issue Tangbuy JWT</li>
 *   <li>Else if ACTIVE offline token exists → silent provision + bind → issue JWT</li>
 *   <li>Else if app is already installed in Admin → token-exchange session→offline,
 *       save + bind, then issue JWT (no top-level OAuth redirect)</li>
 *   <li>Else → {@code NEED_OAUTH} so the client can open login-free install</li>
 * </ol>
 */
@Slf4j
@Service
public class ShopifySessionTokenService {

    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private UserShopRepository userShopRepository;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyMerchantProvisionService merchantProvisionService;
    @Resource
    private ShopifyAuthComponent shopifyAuthComponent;
    @Resource
    private PlatformTokenService platformTokenService;
    @Resource
    private ShopifyPlatformLoginService shopifyPlatformLoginService;

    public Map<String, Object> exchange(String sessionToken) {
        return exchange(sessionToken, null);
    }

    public Map<String, Object> exchange(String sessionToken, String tangbuyToken) {
        if (StringUtils.isBlank(sessionToken)) {
            throw new CustomException("sessionToken is required", 400, "MISSING_SESSION_TOKEN");
        }
        if (StringUtils.isBlank(shopifyProperties.getApiSecret())
                || StringUtils.isBlank(shopifyProperties.getApiKey())) {
            throw new CustomException("Shopify app credentials not configured", 500, "SHOPIFY_CONFIG");
        }

        String rawSession = sessionToken.trim();
        Claims shopifyClaims = verifyShopifySessionToken(rawSession);
        String dest = shopifyClaims.get("dest", String.class);
        String shopDomain = destToShopDomain(dest);
        if (StringUtils.isBlank(shopDomain)) {
            throw new CustomException("Invalid session token dest", 401, "INVALID_SESSION_TOKEN");
        }
        String shopName = toShopName(shopDomain);

        Long userId;
        String email = null;
        Oauth2TokenResponse platformToken = null;
        String responseAccessToken = null;
        PlatformTokenService.PlatformUser platformUser = platformTokenService.verify(stripBearer(tangbuyToken));
        if (platformUser != null && platformUser.getUserId() != null) {
            userId = platformUser.getUserId();
            email = platformUser.getEmail();
            responseAccessToken = stripBearer(tangbuyToken);
            if (shopifyStoreAuthService.findActiveByShopDomain(shopDomain).isEmpty()) {
                tryAcquireOfflineViaSessionToken(shopName, shopDomain, rawSession);
            }
            userShopRepository.updateOwnerByShopName(userId, shopName, shopDomain);
            if (userShopRepository.findByShopName(shopName).isEmpty()) {
                userShopRepository.upsertBinding(userId, shopName, shopDomain);
            }
        } else {
            Optional<ShopifyStoreAuth> auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain);
            if (auth.isEmpty() && !tryAcquireOfflineViaSessionToken(shopName, shopDomain, rawSession)) {
                log.info("Session token exchange: NEED_OAUTH shopName={}", shopName);
                Map<String, Object> need = new LinkedHashMap<>();
                need.put("status", "ERROR");
                need.put("code", "NEED_OAUTH");
                need.put("message",
                        "Shop is installed on Shopify but not linked yet. Complete Connect once.");
                need.put("shopDomain", shopDomain);
                need.put("shopName", shopName);
                need.put("shopifyInstalled", true);
                need.put("installPath", "/api/plugin/shopify/auth/install-embedded?shop="
                        + java.net.URLEncoder.encode(shopDomain, java.nio.charset.StandardCharsets.UTF_8));
                return need;
            }
            ShopifyStoreAuth activeAuth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain)
                    .or(() -> shopifyStoreAuthService.findActiveByShopName(shopName))
                    .orElseThrow(() -> new CustomException("Shop is not authorized yet", 409, "NEED_OAUTH"));
            ShopifyMerchantProvisionService.ShopProfile profile =
                    merchantProvisionService.fetchShopProfile(shopName, shopDomain, activeAuth.getAccessToken());
            platformToken = shopifyPlatformLoginService.login(shopName, profile.email(), profile.name());
            userId = parseUserId(platformToken);
            email = profile.email();
            userShopRepository.updateOwnerByShopName(userId, shopName, shopDomain);
            if (userShopRepository.findByShopName(shopName).isEmpty()) {
                userShopRepository.upsertBinding(userId, shopName, shopDomain);
            }
            responseAccessToken = platformToken.getToken();
        }

        if (StringUtils.isBlank(responseAccessToken)) {
            throw new CustomException("Platform token missing", 401, "UNAUTHENTICATED");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("accessToken", responseAccessToken);
        if (platformToken != null) {
            out.put("refreshToken", platformToken.getRefreshToken());
            out.put("tokenHead", platformToken.getTokenHead());
            out.put("expiresIn", platformToken.getExpiresIn());
        }
        out.put("shopName", shopName);
        out.put("shopDomain", shopDomain);
        out.put("userId", userId);
        out.put("email", email);
        return out;
    }

    private Long parseUserId(Oauth2TokenResponse token) {
        if (token == null || StringUtils.isBlank(token.getUuid())) {
            throw new CustomException("Platform token user missing", 401, "UNAUTHENTICATED");
        }
        try {
            return Long.valueOf(token.getUuid());
        } catch (NumberFormatException e) {
            throw new CustomException("Platform token user invalid", 401, "UNAUTHENTICATED");
        }
    }

    private static String stripBearer(String token) {
        if (StringUtils.isBlank(token)) return null;
        return token.trim().replaceFirst("(?i)^Bearer\\s+", "");
    }

    /**
     * Session token → expiring offline Admin API token, then persist.
     * @return true when an ACTIVE offline token is available afterwards
     */
    private boolean tryAcquireOfflineViaSessionToken(String shopName, String shopDomain,
                                                     String sessionToken) {
        try {
            JSONObject tokenJson = shopifyAuthComponent.exchangeSessionTokenForOffline(
                    shopDomain, sessionToken);
            Long authId = shopifyStoreAuthService.saveActiveAuth(shopName, shopDomain, tokenJson);
            log.info("Acquired offline token via session exchange shopName={} authId={}",
                    shopName, authId);
            return true;
        } catch (Exception e) {
            log.warn("Session→offline token exchange failed shopName={}: {}",
                    shopName, e.getMessage());
            return shopifyStoreAuthService.findActiveByShopDomain(shopDomain).isPresent();
        }
    }

    private Claims verifyShopifySessionToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(
                    shopifyProperties.getApiSecret().getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Object aud = claims.get("aud");
            String apiKey = shopifyProperties.getApiKey();
            boolean audOk = false;
            if (aud instanceof String s) {
                audOk = apiKey.equals(s);
            } else if (aud instanceof java.util.Collection<?> c) {
                audOk = c.stream().anyMatch(v -> apiKey.equals(String.valueOf(v)));
            }
            if (!audOk) {
                throw new CustomException("Invalid session token audience", 401, "INVALID_SESSION_TOKEN");
            }
            return claims;
        } catch (CustomException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Shopify session token verify failed: {}", e.getMessage());
            throw new CustomException("Invalid or expired session token", 401, "INVALID_SESSION_TOKEN");
        }
    }

    static String destToShopDomain(String dest) {
        if (StringUtils.isBlank(dest)) return null;
        String d = dest.trim();
        if (d.startsWith("https://")) d = d.substring("https://".length());
        if (d.startsWith("http://")) d = d.substring("http://".length());
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        d = d.toLowerCase();
        return ShopifyGraphqlClient.normalizeDomain(d);
    }

    private static String toShopName(String shopDomain) {
        return StringUtils.removeEnd(shopDomain, ".myshopify.com");
    }
}
