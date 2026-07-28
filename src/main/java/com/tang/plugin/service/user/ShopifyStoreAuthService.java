package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.ShopifyAuthStatus;
import com.tang.plugin.repository.ShopifyStoreAuthRepository;
import com.tang.plugin.service.order.external.component.ShopifyAuthComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ShopifyStoreAuthService {

    /** Refresh a bit before hard expiry so in-flight Admin API calls don't race the clock. */
    private static final long REFRESH_SKEW_SECONDS = 120L;

    private final ConcurrentHashMap<String, Object> shopLocks = new ConcurrentHashMap<>();

    @Resource
    private ShopifyStoreAuthRepository shopifyStoreAuthRepository;
    @Resource
    private ShopifyAuthComponent shopifyAuthComponent;

    public Long saveActiveAuth(String shopName, String shopDomain, String accessToken, String scope) {
        JSONObject tokenJson = new JSONObject();
        tokenJson.put("access_token", accessToken);
        tokenJson.put("scope", scope);
        return saveActiveAuth(shopName, shopDomain, tokenJson);
    }

    public Long saveActiveAuth(String shopName, String shopDomain, JSONObject tokenJson) {
        if (StringUtils.isAnyBlank(shopName, shopDomain) || tokenJson == null) {
            throw new CustomException("saveActiveAuth missing fields, shopDomain=" + shopDomain);
        }
        String accessToken = StringUtils.trimToEmpty(tokenJson.getString("access_token"));
        if (StringUtils.isBlank(accessToken)) {
            throw new CustomException("saveActiveAuth missing access_token, shopDomain=" + shopDomain);
        }
        Instant now = Instant.now();
        ShopifyStoreAuth auth = new ShopifyStoreAuth()
                .setShopName(shopName)
                .setShopDomain(shopDomain.toLowerCase())
                .setAccessToken(accessToken)
                .setScope(tokenJson.getString("scope"))
                .setStatus(ShopifyAuthStatus.ACTIVE)
                .setAuthorizedAt(now)
                .setRefreshToken(StringUtils.trimToNull(tokenJson.getString("refresh_token")))
                .setAccessTokenExpiresAt(expiresAtFromSeconds(now, tokenJson.getInteger("expires_in")))
                .setRefreshTokenExpiresAt(
                        expiresAtFromSeconds(now, tokenJson.getInteger("refresh_token_expires_in")));
        return shopifyStoreAuthRepository.upsertActive(auth);
    }

    public Optional<ShopifyStoreAuth> findActiveByShopDomain(String shopDomain) {
        return shopifyStoreAuthRepository.findActiveByShopDomain(shopDomain);
    }

    public Optional<ShopifyStoreAuth> findActiveByShopName(String shopName) {
        return shopifyStoreAuthRepository.findActiveByShopName(shopName);
    }

    public List<ShopifyStoreAuth> listActive() {
        return shopifyStoreAuthRepository.listActive();
    }

    public void markUninstalledByShopDomain(String shopDomain) {
        if (StringUtils.isBlank(shopDomain)) {
            throw new CustomException("markUninstalled shopDomain blank");
        }
        shopifyStoreAuthRepository.markUninstalled(shopDomain);
        log.info("Auth uninstalled shopDomain={}", shopDomain);
    }

    /**
     * Ensure the shop's offline access token is usable against Admin API:
     * migrate legacy non-expiring tokens once, then refresh when near expiry.
     */
    public ShopifyStoreAuth ensureFreshAccessToken(ShopifyStoreAuth auth) {
        if (auth == null || StringUtils.isAnyBlank(auth.getShopDomain(), auth.getAccessToken())) {
            throw new CustomException("ensureFreshAccessToken missing auth");
        }
        String lockKey = auth.getShopDomain().toLowerCase();
        Object lock = shopLocks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            ShopifyStoreAuth latest = shopifyStoreAuthRepository
                    .findActiveByShopDomain(lockKey)
                    .orElse(auth);
            Instant now = Instant.now();
            if (StringUtils.isBlank(latest.getRefreshToken())) {
                return migrateLegacyToken(latest);
            }
            if (needsRefresh(latest, now)) {
                return refreshToken(latest);
            }
            return latest;
        }
    }

    public Optional<ShopifyStoreAuth> findActiveFreshByShopName(String shopName) {
        return findActiveByShopName(shopName).map(this::ensureFreshAccessToken);
    }

    public Optional<ShopifyStoreAuth> findActiveFreshByShopDomain(String shopDomain) {
        return findActiveByShopDomain(shopDomain).map(this::ensureFreshAccessToken);
    }

    private ShopifyStoreAuth migrateLegacyToken(ShopifyStoreAuth auth) {
        log.warn("Migrating shopDomain={} to expiring offline token (legacy non-expiring row)",
                auth.getShopDomain());
        try {
            JSONObject tokenJson = shopifyAuthComponent.migrateToExpiringOfflineToken(
                    auth.getShopDomain(), auth.getAccessToken());
            saveActiveAuth(auth.getShopName(), auth.getShopDomain(), tokenJson);
            return shopifyStoreAuthRepository.findActiveByShopDomain(auth.getShopDomain())
                    .orElseThrow(() -> new CustomException(
                            "Shopify token migrate persisted but row missing, shopDomain="
                                    + auth.getShopDomain()));
        } catch (CustomException e) {
            throw new CustomException(
                    "Shopify offline token migration failed for shopName=" + auth.getShopName()
                            + "; re-install the app from /authorize so a new expiring token is issued"
                            + "; cause=" + e.getMessage(),
                    e);
        }
    }

    private ShopifyStoreAuth refreshToken(ShopifyStoreAuth auth) {
        log.info("Refreshing expiring offline token shopDomain={}", auth.getShopDomain());
        try {
            JSONObject tokenJson = shopifyAuthComponent.refreshAccessToken(
                    auth.getShopDomain(), auth.getRefreshToken());
            // Keep prior scope if refresh response omits it.
            if (StringUtils.isBlank(tokenJson.getString("scope"))
                    && StringUtils.isNotBlank(auth.getScope())) {
                tokenJson.put("scope", auth.getScope());
            }
            saveActiveAuth(auth.getShopName(), auth.getShopDomain(), tokenJson);
            return shopifyStoreAuthRepository.findActiveByShopDomain(auth.getShopDomain())
                    .orElseThrow(() -> new CustomException(
                            "Shopify token refresh persisted but row missing, shopDomain="
                                    + auth.getShopDomain()));
        } catch (CustomException e) {
            throw new CustomException(
                    "Shopify offline token refresh failed for shopName=" + auth.getShopName()
                            + "; re-install the app from /authorize"
                            + "; cause=" + e.getMessage(),
                    e);
        }
    }

    private static boolean needsRefresh(ShopifyStoreAuth auth, Instant now) {
        Instant accessExp = auth.getAccessTokenExpiresAt();
        if (accessExp == null) {
            // Expiring token row without expiry metadata — refresh to re-anchor.
            return true;
        }
        return !accessExp.isAfter(now.plusSeconds(REFRESH_SKEW_SECONDS));
    }

    private static Instant expiresAtFromSeconds(Instant now, Integer expiresInSeconds) {
        if (expiresInSeconds == null || expiresInSeconds <= 0) {
            return null;
        }
        return now.plusSeconds(expiresInSeconds.longValue());
    }
}
