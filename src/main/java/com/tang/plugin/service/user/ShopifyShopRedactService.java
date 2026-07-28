package com.tang.plugin.service.user;

import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.repository.ShopifyStoreAuthRepository;
import com.tang.plugin.repository.UserShopRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Purges shop-scoped data for GDPR {@code shop/redact} (and deep cleanup after uninstall).
 *
 * <p>Does not delete Tangbuy {@code users} rows — only merchant shop mirror, bindings,
 * and auth tokens for the given myshopify domain.
 */
@Slf4j
@Service
public class ShopifyShopRedactService {

    /** Soft-delete targets with updated_at. */
    private static final String[] SOFT_DELETE_WITH_UPDATED_AT = {
            "UPDATE third_platform_product SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE product_source_binding SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE variant_sku_binding SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE pricing_template SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE logistics_template SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE product_logistics_profile SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE logistics_accept_decision SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE third_platform_order SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE third_platform_order_line SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE shop_product_binding SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE shop_product_match_candidate SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
            "UPDATE product_publish_record SET del_flag = 1, updated_at = ? WHERE shop_name = ? AND del_flag = 0",
    };

    /** Soft-delete targets without updated_at column. */
    private static final String[] SOFT_DELETE_FLAG_ONLY = {
            "UPDATE third_platform_sku SET del_flag = 1 WHERE shop_name = ? AND del_flag = 0",
            "UPDATE third_platform_product_media SET del_flag = 1 WHERE shop_name = ? AND del_flag = 0",
    };

    @Resource
    private ShopifyStoreAuthRepository shopifyStoreAuthRepository;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private UserShopRepository userShopRepository;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * @return approximate number of DB rows touched (bindings + soft-deletes + auth)
     */
    @Transactional
    public int redactShop(String shopDomain) {
        if (StringUtils.isBlank(shopDomain)) {
            return 0;
        }
        String domain = shopDomain.trim().toLowerCase();
        Optional<ShopifyStoreAuth> authOpt = shopifyStoreAuthRepository.findByShopDomain(domain);
        String shopName = authOpt.map(ShopifyStoreAuth::getShopName).orElse(null);
        if (StringUtils.isBlank(shopName)) {
            // Domain may already be gone; still try common shopName = subdomain heuristic.
            shopName = domain.replace(".myshopify.com", "");
        }

        int touched = 0;
        try {
            shopifyStoreAuthService.markUninstalledByShopDomain(domain);
            touched += 1;
        } catch (Exception e) {
            log.warn("shop/redact markUninstalled failed shopDomain={}: {}", domain, e.getMessage());
        }

        touched += userShopRepository.deleteByShopName(shopName);

        Timestamp now = Timestamp.from(Instant.now());
        for (String sql : SOFT_DELETE_WITH_UPDATED_AT) {
            try {
                touched += jdbcTemplate.update(sql, now, shopName);
            } catch (Exception e) {
                log.debug("shop/redact skip sql shopName={} err={}", shopName, e.getMessage());
            }
        }
        for (String sql : SOFT_DELETE_FLAG_ONLY) {
            try {
                touched += jdbcTemplate.update(sql, shopName);
            } catch (Exception e) {
                log.debug("shop/redact skip sql shopName={} err={}", shopName, e.getMessage());
            }
        }

        // Hard-clear tokens again in case markUninstalled missed alternate casings.
        try {
            touched += jdbcTemplate.update(
                    "UPDATE shopify_store_auth SET access_token = '', updated_at = ? WHERE shop_domain = ?",
                    now, domain);
        } catch (Exception e) {
            log.debug("shop/redact token clear skip: {}", e.getMessage());
        }

        log.info("Shopify shop redacted shopDomain={} shopName={} touched={}", domain, shopName, touched);
        return touched;
    }
}
