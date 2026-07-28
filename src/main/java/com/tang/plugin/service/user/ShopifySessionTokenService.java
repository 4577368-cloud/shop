package com.tang.plugin.service.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.JwtAuthProperties;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.entity.user.UserShop;
import com.tang.plugin.repository.AppUserRepository;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.service.auth.JwtService;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
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
    private AppUserRepository appUserRepository;
    @Resource
    private JwtService jwtService;
    @Resource
    private JwtAuthProperties jwtAuthProperties;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyMerchantProvisionService merchantProvisionService;

    public Map<String, Object> exchange(String sessionToken) {
        if (StringUtils.isBlank(sessionToken)) {
            throw new CustomException("sessionToken is required", 400, "MISSING_SESSION_TOKEN");
        }
        if (StringUtils.isBlank(shopifyProperties.getApiSecret())
                || StringUtils.isBlank(shopifyProperties.getApiKey())) {
            throw new CustomException("Shopify app credentials not configured", 500, "SHOPIFY_CONFIG");
        }

        Claims shopifyClaims = verifyShopifySessionToken(sessionToken.trim());
        String dest = shopifyClaims.get("dest", String.class);
        String shopDomain = destToShopDomain(dest);
        if (StringUtils.isBlank(shopDomain)) {
            throw new CustomException("Invalid session token dest", 401, "INVALID_SESSION_TOKEN");
        }
        String shopName = toShopName(shopDomain);

        Long userId;
        Optional<UserShop> binding = userShopRepository.findByShopName(shopName);
        if (binding.isPresent()) {
            userId = binding.get().getUserId();
        } else {
            Optional<ShopifyStoreAuth> auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain);
            if (auth.isEmpty()) {
                log.info("Session token exchange: NEED_OAUTH shopName={}", shopName);
                Map<String, Object> need = new LinkedHashMap<>();
                need.put("status", "ERROR");
                need.put("code", "NEED_OAUTH");
                need.put("message", "Shop needs Shopify OAuth authorization first");
                need.put("shopDomain", shopDomain);
                need.put("shopName", shopName);
                need.put("installPath", "/api/plugin/shopify/auth/install-embedded?shop="
                        + java.net.URLEncoder.encode(shopDomain, java.nio.charset.StandardCharsets.UTF_8));
                return need;
            }
            userId = merchantProvisionService.ensureUserBoundToShop(shopName, shopDomain);
        }

        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(() -> new CustomException("User not found", 401, "UNAUTHENTICATED"));

        String accessToken = jwtService.generateAccessToken(userId, user.getEmail(), shopName, shopDomain);
        long expiresIn = jwtAuthProperties.getJwt().getAccessTtlSeconds();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "OK");
        out.put("accessToken", accessToken);
        out.put("expiresIn", expiresIn);
        out.put("shopName", shopName);
        out.put("shopDomain", shopDomain);
        out.put("userId", userId);
        out.put("email", user.getEmail());
        return out;
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
