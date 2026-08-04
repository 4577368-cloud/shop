package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.entity.user.UserOauthState;
import com.tang.plugin.domain.entity.user.UserShop;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.UserOauthStateRepository;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.service.auth.JwtService;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import com.tang.plugin.service.order.external.component.ShopifyAuthComponent;
import com.tang.plugin.service.product.ProductSyncService;
import com.tang.plugin.service.webhook.component.ShopifyWebhookComponent;
import com.tang.plugin.utils.ShopifyHmacUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shopify OAuth install / callback orchestration. Fulfillment mount explicitly skipped.
 *
 * <p>P2 changes:
 * <ul>
 *   <li>{@code buildInstallUrl} now requires a {@code userId}; the generated state is persisted
 *       (SHA-256 hashed) in {@code user_oauth_state} so the callback can both verify CSRF and
 *       attribute the binding to the initiating user.</li>
 *   <li>{@code handleCallback} validates the state, marks it consumed (one-time use), and writes
 *       a {@code user_shop} binding row. If the shop is already bound to a different user, the
 *       binding is refused with {@code SHOP_ALREADY_BOUND}.</li>
 *   <li>{@code listActiveShops} is now scoped to a {@code userId} — returns only shops bound to
 *       that user. The global unscoped overload is retained for backward compatibility during
 *       transition (e.g. internal admin tooling) but should not be exposed to end users.</li>
 * </ul>
 */
@Slf4j
@Service
public class ShopifyAuthService {

    /** Standalone authorize / Connect — return to frontend /authorize after OAuth. */
    public static final String FLOW_STANDALONE = "STANDALONE";
    /** Admin / App Store embedded install — bounce back into Admin. */
    public static final String FLOW_EMBEDDED = "EMBEDDED";
    /** Standalone "Login with Shopify" — set session cookies, then return_to. */
    public static final String FLOW_LOGIN = "LOGIN";

    /** OAuth state TTL — must be long enough for the Shopify consent screen but short enough to limit replay. */
    private static final long OAUTH_STATE_TTL_SECONDS = 600L; // 10 minutes

    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private ShopifyAuthComponent shopifyAuthComponent;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyWebhookComponent shopifyWebhookComponent;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ProductSyncService productSyncService;
    @Resource
    private UserShopRepository userShopRepository;
    @Resource
    private UserOauthStateRepository userOauthStateRepository;
    @Resource
    private JwtService jwtService;
    @Resource
    private ShopifyMerchantProvisionService merchantProvisionService;

    /**
     * Read-only auth status for a shop, used by the frontend to restore state after OAuth redirect.
     * Returns only non-sensitive fields (never the access token). Unknown/invalid shops report
     * {@code authorized=false} instead of failing.
     *
     * <p>P2.1 hardening: when {@code userId} is provided (caller is authenticated), the shop must
     * also be bound to that user. A shop authorized under another account reports
     * {@code authorized=false, status="NOT_BOUND"} so its existence is not leaked. Callers without
     * a JWT (legacy/transition flows) still receive the legacy behavior — the endpoint remains
     * in PROTECTED_EXACT_PATHS so in practice a JWT is always present.
     */
    public Map<String, Object> getShopStatus(String shop, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String shopDomain = ShopifyGraphqlClient.normalizeDomain(shop);
        if (StringUtils.isBlank(shopDomain) || !shopDomain.endsWith(".myshopify.com")) {
            result.put("authorized", false);
            result.put("shopDomain", shop);
            result.put("status", "INVALID");
            return result;
        }
        shopDomain = shopDomain.toLowerCase();
        Optional<ShopifyStoreAuth> auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain);
        if (auth.isEmpty()) {
            result.put("authorized", false);
            result.put("shopDomain", shopDomain);
            result.put("status", "NONE");
            return result;
        }
        ShopifyStoreAuth a = auth.get();
        // P2.1: enforce user binding. If the caller is authenticated but the shop is bound to a
        // different user (or not bound at all), do not reveal that the shop is authorized.
        if (userId != null) {
            Optional<UserShop> binding = userShopRepository.findByShopName(a.getShopName());
            if (binding.isEmpty() || !binding.get().getUserId().equals(userId)) {
                log.warn("Shop status denied: shopDomain={} requested by userId={} but bound to {}",
                        shopDomain, userId, binding.map(UserShop::getUserId).orElse(null));
                result.put("authorized", false);
                result.put("shopDomain", shopDomain);
                result.put("status", "NOT_BOUND");
                return result;
            }
        }
        result.put("authorized", true);
        result.put("shopName", a.getShopName());
        result.put("shopDomain", a.getShopDomain());
        result.put("status", a.getStatus().name());
        result.put("authorizedAt", a.getAuthorizedAt());
        result.put("productCount", thirdPlatformProductRepository.countByShop(a.getShopName()));
        return result;
    }

    /** Legacy overload — retains the old signature for any internal callers that don't have a userId. */
    public Map<String, Object> getShopStatus(String shop) {
        return getShopStatus(shop, null);
    }

    /**
     * Active authorized shops bound to the given user. Never returns access tokens.
     * P2: scoped by user_shop binding.
     */
    public List<Map<String, Object>> listActiveShops(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<UserShop> bindings = userShopRepository.listByUserId(userId);
        List<Map<String, Object>> out = new ArrayList<>(bindings.size());
        for (UserShop b : bindings) {
            Optional<ShopifyStoreAuth> auth = shopifyStoreAuthService.findActiveByShopName(b.getShopName());
            if (auth.isEmpty()) {
                // Bound row exists but shopify_store_auth is gone (e.g. uninstalled). Skip silently.
                continue;
            }
            ShopifyStoreAuth a = auth.get();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shopName", a.getShopName());
            row.put("shopDomain", a.getShopDomain());
            row.put("authorizedAt", a.getAuthorizedAt());
            row.put("productCount", thirdPlatformProductRepository.countByShop(a.getShopName()));
            row.put("boundAt", b.getBoundAt());
            out.add(row);
        }
        return out;
    }

    /**
     * Build the Shopify OAuth install URL. The state nonce is persisted (hashed) so the callback
     * can verify CSRF and bind the resulting shop to the initiating user.
     *
     * @param userId the authenticated user initiating the install (must not be null)
     * @param shop   the shop domain entered by the user (e.g. "my-shop" or "my-shop.myshopify.com")
     * @return the absolute Shopify OAuth consent URL
     */
    public String buildInstallUrl(Long userId, String shop) {
        if (userId == null) {
            throw new CustomException("User must be authenticated to install a shop", 401, "UNAUTHENTICATED");
        }
        return buildInstallUrlInternal(userId, shop, FLOW_STANDALONE);
    }

    /**
     * Login-free install for Shopify Admin / App Store embedded path.
     * State uses sentinel {@link ShopifyMerchantProvisionService#AUTO_PROVISION_USER_ID};
     * callback auto-creates/binds a Tangbuy user from the shop email.
     */
    public String buildInstallUrlAutoProvision(String shop) {
        return buildInstallUrlInternal(
                ShopifyMerchantProvisionService.AUTO_PROVISION_USER_ID, shop, FLOW_EMBEDDED);
    }

    /** Standalone Login with Shopify (auto-provision + session cookies on callback). */
    public String buildInstallUrlLogin(String shop) {
        return buildInstallUrlInternal(
                ShopifyMerchantProvisionService.AUTO_PROVISION_USER_ID, shop, FLOW_LOGIN);
    }

    private String buildInstallUrlInternal(Long userId, String shop, String flow) {
        assertConfigured();
        String shopDomain = normalizeAndValidateShop(shop);
        String rawState = UUID.randomUUID().toString().replace("-", "");
        String stateHash = jwtService.hashToken(rawState);
        Instant expiresAt = Instant.now().plusSeconds(OAUTH_STATE_TTL_SECONDS);
        userOauthStateRepository.insert(stateHash, userId, shopDomain, flow, expiresAt);
        log.info("Shopify install redirect prepared userId={} shopDomain={} flow={} stateExpiresAt={}",
                userId, shopDomain, flow, expiresAt);
        return shopifyAuthComponent.buildInstallRedirectUrl(shopDomain, rawState);
    }

    public Map<String, Object> handleCallback(Map<String, String> queryParams) {
        assertConfigured();
        if (queryParams == null || queryParams.isEmpty()) {
            throw new CustomException("Shopify callback params empty");
        }
        String shopHint = queryParams.get("shop");
        String hmac = queryParams.get("hmac");
        String apiSecret = StringUtils.trimToEmpty(shopifyProperties.getApiSecret());
        if (!ShopifyHmacUtils.verifyOAuthQueryHmac(queryParams, hmac, apiSecret)) {
            log.error("Shopify callback HMAC invalid shop={} paramKeys={}",
                    shopHint, queryParams.keySet());
            throw new CustomException(
                    "Shopify callback HMAC invalid. Check TANG_PLUGIN_SHOPIFY_API_SECRET matches Partner App client secret.");
        }

        String shopDomain = normalizeAndValidateShop(queryParams.get("shop"));
        String code = queryParams.get("code");
        String rawState = queryParams.get("state");
        if (StringUtils.isBlank(code)) {
            throw new CustomException("Shopify callback code blank, shopDomain=" + shopDomain);
        }

        // P2: validate state (CSRF + user binding). State is one-time use — mark consumed atomically.
        UserOauthState oauthState = consumeState(rawState, shopDomain);
        String shopName = toShopName(shopDomain);

        // Refuse binding / token overwrite if the shop is already owned by another user.
        // Applies to both login and auto-provision paths (prevent credential hijack).
        boolean autoProvision = oauthState.getUserId() == null
                || oauthState.getUserId() <= ShopifyMerchantProvisionService.AUTO_PROVISION_USER_ID;
        Optional<UserShop> existingBinding = userShopRepository.findByShopName(shopName);
        if (existingBinding.isPresent()) {
            Long ownerId = existingBinding.get().getUserId();
            boolean sameOwner = !autoProvision && ownerId.equals(oauthState.getUserId());
            boolean reclaimByAuto = autoProvision; // same shop reinstall: allow token refresh + keep owner
            if (!sameOwner && !reclaimByAuto) {
                log.warn("Shop {} already bound to user {} (attempted bind by user {})",
                        shopName, ownerId, oauthState.getUserId());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "SHOP_ALREADY_BOUND");
                result.put("shopDomain", shopDomain);
                result.put("shopName", shopName);
                result.put("authId", null);
                result.put("oauthFlow", resolveFlow(oauthState));
                result.put("fulfillmentMounted", false);
                result.put("note", "Shop is already bound to another account. Contact support if you believe this is an error.");
                return result;
            }
            if (autoProvision) {
                // Reinstall from Admin: refresh offline token, keep existing owner.
                log.info("Auto-provision reinstall: keep owner userId={} shopName={}", ownerId, shopName);
            }
        } else if (!autoProvision) {
            // logged-in install of unbound shop — continue
        }

        JSONObject tokenJson;
        try {
            tokenJson = shopifyAuthComponent.exchangeAccessToken(shopDomain, code);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Shopify token exchange unexpected shopDomain={}", shopDomain, e);
            throw new CustomException("Shopify token exchange failed, shopDomain=" + shopDomain
                    + ", cause=" + e.getMessage(), e);
        }
        String accessToken = tokenJson.getString("access_token");
        if (StringUtils.isBlank(tokenJson.getString("refresh_token"))) {
            log.warn("Shopify callback returned non-expiring token for shopDomain={}; Admin API may reject it",
                    shopDomain);
        }

        Long authId;
        try {
            authId = shopifyStoreAuthService.saveActiveAuth(shopName, shopDomain, tokenJson);
        } catch (Exception e) {
            log.error("Shopify auth persist failed shopDomain={}", shopDomain, e);
            throw new CustomException("Shopify auth save failed, shopDomain=" + shopDomain
                    + ", cause=" + e.getMessage(), e);
        }
        log.info("Shopify auth saved shopDomain={} shopName={} authId={} expiring={}",
                shopDomain, shopName, authId, StringUtils.isNotBlank(tokenJson.getString("refresh_token")));

        Long boundUserId;
        try {
            boundUserId = merchantProvisionService.resolveOwnerAfterOauth(
                    oauthState.getUserId(), shopName, shopDomain, accessToken);
        } catch (CustomException e) {
            if ("SHOP_ALREADY_BOUND".equals(e.getCode())) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "SHOP_ALREADY_BOUND");
                result.put("shopDomain", shopDomain);
                result.put("shopName", shopName);
                result.put("authId", authId);
                result.put("oauthFlow", resolveFlow(oauthState));
                result.put("fulfillmentMounted", false);
                result.put("note", e.getMessage());
                return result;
            }
            throw e;
        }
        log.info("Shop bound userId={} shopName={} autoProvision={} flow={}",
                boundUserId, shopName, autoProvision, resolveFlow(oauthState));

        // Fulfillment mount intentionally skipped in phase-2.
        try {
            shopifyWebhookComponent.registerDefaultWebhooks(shopName, shopDomain, accessToken);
        } catch (Exception e) {
            log.error("Shopify webhook register after auth failed shopDomain={}", shopDomain, e);
        }

        // Fire-and-forget product pull so the mirror is populated right after authorization.
        // Runs async; never blocks the callback redirect and never fails the auth on error.
        try {
            productSyncService.asyncFullSyncShopify(shopName);
        } catch (Exception e) {
            log.error("Trigger post-auth product sync failed shopName={}", shopName, e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("shopDomain", shopDomain);
        result.put("shopName", shopName);
        result.put("authId", authId);
        result.put("boundToUserId", boundUserId);
        result.put("oauthFlow", resolveFlow(oauthState));
        result.put("fulfillmentMounted", false);
        result.put("note", "Auth saved. Webhooks registered (app/uninstalled, products/create|update|delete).");
        return result;
    }

    /**
     * Validate the OAuth state nonce: must exist, be unconsumed, unexpired, and match the shop
     * domain returned by Shopify. Atomically marks it consumed to prevent replay.
     * Returns the resolved state record (contains userId + shopDomain).
     */
    private UserOauthState consumeState(String rawState, String shopDomainFromCallback) {
        if (StringUtils.isBlank(rawState)) {
            throw new CustomException("Shopify callback state missing", 400, "OAUTH_STATE_MISSING");
        }
        String stateHash = jwtService.hashToken(rawState);
        Optional<UserOauthState> opt = userOauthStateRepository.findActiveByStateHash(stateHash);
        if (opt.isEmpty()) {
            throw new CustomException("Shopify callback state invalid or expired", 400, "OAUTH_STATE_INVALID");
        }
        UserOauthState state = opt.get();
        // Verify the shop domain returned by Shopify matches the one recorded at install time.
        // This prevents a state issued for shop A from being used to authorize shop B.
        if (!shopDomainFromCallback.equalsIgnoreCase(state.getShopDomain())) {
            log.warn("OAuth state shop mismatch: state.shop={} callback.shop={}",
                    state.getShopDomain(), shopDomainFromCallback);
            throw new CustomException("Shopify callback state shop mismatch", 400, "OAUTH_STATE_SHOP_MISMATCH");
        }
        if (!userOauthStateRepository.markConsumed(state.getId())) {
            // Concurrent callback already consumed this state — refuse the replay.
            throw new CustomException("Shopify callback state already consumed", 400, "OAUTH_STATE_REPLAYED");
        }
        return state;
    }

    private void assertConfigured() {
        String apiKey = StringUtils.trimToEmpty(shopifyProperties.getApiKey());
        String apiSecret = StringUtils.trimToEmpty(shopifyProperties.getApiSecret());
        if (StringUtils.isAnyBlank(apiKey, apiSecret)) {
            throw new CustomException(
                    "Shopify api-key/api-secret not configured on server (check Render env TANG_PLUGIN_SHOPIFY_API_KEY / _API_SECRET)");
        }
        if (StringUtils.isBlank(shopifyProperties.getRedirectUri())) {
            throw new CustomException("Shopify redirect-uri not configured (TANG_PLUGIN_SHOPIFY_REDIRECT_URI)");
        }
    }

    private static String normalizeAndValidateShop(String shop) {
        String domain = ShopifyGraphqlClient.normalizeDomain(shop);
        if (StringUtils.isBlank(domain) || !domain.endsWith(".myshopify.com")) {
            throw new CustomException("Invalid Shopify shop domain: " + shop);
        }
        return domain.toLowerCase();
    }

    private static String toShopName(String shopDomain) {
        return StringUtils.removeEnd(shopDomain, ".myshopify.com");
    }

    /**
     * Resolve post-OAuth redirect flow. Legacy rows without {@code flow} fall back by
     * auto-provision sentinel (embedded/login historically shared it) → EMBEDDED;
     * authenticated install → STANDALONE.
     */
    static String resolveFlow(UserOauthState state) {
        if (state == null) {
            return FLOW_STANDALONE;
        }
        if (StringUtils.isNotBlank(state.getFlow())) {
            return state.getFlow().trim().toUpperCase();
        }
        boolean autoProvision = state.getUserId() == null
                || state.getUserId() <= ShopifyMerchantProvisionService.AUTO_PROVISION_USER_ID;
        return autoProvision ? FLOW_EMBEDDED : FLOW_STANDALONE;
    }
}
