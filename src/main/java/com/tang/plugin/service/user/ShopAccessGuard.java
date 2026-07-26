package com.tang.plugin.service.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.UserShop;
import com.tang.plugin.repository.UserShopRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Shop access guard: verifies that a given {@code shopName} is bound to the calling user.
 *
 * <p>Used by business controllers (match / order / pricing / logistics / catalog / product / sync /
 * sku-align / ranking / procurement) to prevent a logged-in user from reading or mutating another
 * user's shop data by simply passing {@code ?shopName=victim-shop}.
 *
 * <p>Failure mode: throws {@link CustomException} with {@code 403 FORBIDDEN} and does not reveal
 * whether the shop exists (returns the same error whether the shop is unbound or bound to someone
 * else) to avoid enumeration.
 */
@Slf4j
@Service
public class ShopAccessGuard {

    @Resource
    private UserShopRepository userShopRepository;

    /**
     * Assert that {@code shopName} is bound to {@code userId}. Throws 403 if not.
     *
     * @param userId  authenticated user id (from request attribute "userId")
     * @param shopName shopName query/path param from the request; must be non-blank
     * @throws CustomException with code {@code FORBIDDEN} when the shop is not bound to this user
     */
    public void assertOwner(Long userId, String shopName) {
        if (userId == null) {
            // Defensive: should never happen because JwtAuthFilter already verified the token.
            throw new CustomException("Authentication required", 401, "UNAUTHENTICATED");
        }
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("shopName is required", 400, "MISSING_SHOP_NAME");
        }
        Optional<UserShop> binding = userShopRepository.findByShopName(shopName);
        if (binding.isEmpty() || !binding.get().getUserId().equals(userId)) {
            log.warn("Shop access denied: userId={} shopName={}", userId, shopName);
            // Same error whether the shop is unbound or owned by another user (no enumeration).
            throw new CustomException("Shop not bound to current user", 403, "FORBIDDEN");
        }
    }
}
