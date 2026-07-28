package com.tang.plugin.service.user;

import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.AppUser;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.entity.user.UserShop;
import com.tang.plugin.repository.AppUserRepository;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.service.auth.PasswordService;
import com.tang.plugin.service.order.external.client.ShopifyGraphqlClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Silent Tangbuy account provisioning for Shopify App Store / embedded install.
 *
 * <p>When a shop has (or just received) an offline access token but no {@code user_shop} binding,
 * create or reuse a Tangbuy {@code app_user} from the Shopify shop email and bind ownership.
 * Merchants can later set a password via forgot-password using that email.
 */
@Slf4j
@Service
public class ShopifyMerchantProvisionService {

    /** Sentinel user_id in user_oauth_state for login-free embedded OAuth. */
    public static final long AUTO_PROVISION_USER_ID = 0L;

    private static final String SHOP_EMAIL_QUERY = """
            query ShopEmail {
              shop {
                name
                email
                myshopifyDomain
              }
            }
            """;

    @Resource
    private AppUserRepository appUserRepository;
    @Resource
    private UserShopRepository userShopRepository;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ShopifyGraphqlClient shopifyGraphqlClient;
    @Resource
    private PasswordService passwordService;

    /**
     * Ensure a Tangbuy user owns this shop. Uses ACTIVE offline token to read shop email when needed.
     *
     * @return bound user id
     */
    public Long ensureUserBoundToShop(String shopName, String shopDomain) {
        Optional<UserShop> existing = userShopRepository.findByShopName(shopName);
        if (existing.isPresent()) {
            return existing.get().getUserId();
        }

        ShopifyStoreAuth auth = shopifyStoreAuthService.findActiveByShopDomain(shopDomain)
                .or(() -> shopifyStoreAuthService.findActiveByShopName(shopName))
                .orElseThrow(() -> new CustomException(
                        "Shop is not authorized yet", 409, "NEED_OAUTH"));

        ShopProfile profile = fetchShopProfile(shopName, shopDomain, auth.getAccessToken());
        AppUser user = findOrCreateUser(profile);
        UserShop binding = userShopRepository.upsertBinding(user.getId(), shopName, shopDomain);
        log.info("Silent provision bound userId={} shopName={} email={} bindingId={}",
                user.getId(), shopName, user.getEmail(), binding.getId());
        return user.getId();
    }

    /**
     * After OAuth token save: bind to existing userId, or auto-provision when sentinel / missing.
     */
    public Long resolveOwnerAfterOauth(Long oauthUserId, String shopName, String shopDomain,
                                       String accessToken) {
        if (oauthUserId != null && oauthUserId > 0) {
            Optional<UserShop> existing = userShopRepository.findByShopName(shopName);
            if (existing.isPresent() && !existing.get().getUserId().equals(oauthUserId)) {
                throw new CustomException(
                        "Shop is already bound to another account", 409, "SHOP_ALREADY_BOUND");
            }
            userShopRepository.upsertBinding(oauthUserId, shopName, shopDomain);
            return oauthUserId;
        }

        // Login-free embedded OAuth (userId sentinel 0).
        Optional<UserShop> existing = userShopRepository.findByShopName(shopName);
        if (existing.isPresent()) {
            log.info("Auto-provision OAuth: shop already bound shopName={} userId={}",
                    shopName, existing.get().getUserId());
            return existing.get().getUserId();
        }

        ShopProfile profile = fetchShopProfile(shopName, shopDomain, accessToken);
        AppUser user = findOrCreateUser(profile);
        userShopRepository.upsertBinding(user.getId(), shopName, shopDomain);
        log.info("Auto-provision OAuth created binding userId={} shopName={}", user.getId(), shopName);
        return user.getId();
    }

    private ShopProfile fetchShopProfile(String shopName, String shopDomain, String accessToken) {
        String email = null;
        String name = shopName;
        try {
            JSONObject response = shopifyGraphqlClient.execute(
                    shopName, shopDomain, accessToken, SHOP_EMAIL_QUERY, null);
            JSONObject data = response.getJSONObject("data");
            JSONObject shop = data == null ? null : data.getJSONObject("shop");
            if (shop != null) {
                email = StringUtils.trimToNull(shop.getString("email"));
                String shopNameField = StringUtils.trimToNull(shop.getString("name"));
                if (shopNameField != null) {
                    name = shopNameField;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Shopify shop email shopName={}: {}", shopName, e.getMessage());
        }
        if (email == null) {
            // Deterministic synthetic mailbox — unique, stable, usable for later password reset
            // only after merchant updates email in account settings.
            email = "shop+" + shopName.toLowerCase() + "@users.tangbuy.local";
            log.info("Using synthetic email for shopName={}", shopName);
        }
        return new ShopProfile(email.toLowerCase(), name);
    }

    private AppUser findOrCreateUser(ShopProfile profile) {
        Optional<AppUser> existing = appUserRepository.findByEmail(profile.email());
        if (existing.isPresent()) {
            return existing.get();
        }
        // Random unusable password — merchant uses forgot-password / change-password later.
        String randomPassword = UUID.randomUUID().toString().replace("-", "") + "Aa1!";
        AppUser user = new AppUser()
                .setEmail(profile.email())
                .setPasswordHash(passwordService.hash(randomPassword))
                .setName(StringUtils.abbreviate(profile.name(), 120))
                .setLocale("en")
                .setTimezone("UTC")
                .setCurrency("USD")
                .setAiResponseLanguage("en")
                .setStatus("active");
        return appUserRepository.insert(user);
    }

    private record ShopProfile(String email, String name) {}
}
