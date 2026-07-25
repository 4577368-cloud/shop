package com.tang.plugin.controller.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.entity.user.UserShop;
import com.tang.plugin.dto.user.UserShopDtos;
import com.tang.plugin.dto.user.UserShopDtos.UnbindResponse;
import com.tang.plugin.dto.user.UserShopDtos.UserShopResponse;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.UserShopRepository;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * User-center shop management endpoints.
 *
 * <p>All endpoints require JWT auth (JwtAuthFilter protects {@code /api/plugin/user/**}).
 * The current user is resolved from the {@code userId} request attribute.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code GET /api/plugin/user/shops} — list shops bound to the current user (with auth status + product count).</li>
 *   <li>{@code DELETE /api/plugin/user/shops/{shopName}} — unbind a shop (physical delete of the user_shop row;
 *       shopify_store_auth is retained so the shop can be re-bound later without re-OAuth).</li>
 * </ul>
 *
 * <p>Binding a new shop is initiated via {@code GET /api/plugin/shopify/auth/install?shop=...}
 * (protected, injects userId) which redirects to Shopify OAuth.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/user/shops")
public class UserShopController {

    @Resource
    private UserShopRepository userShopRepository;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;

    @GetMapping
    public List<UserShopResponse> list(HttpServletRequest request) {
        Long userId = requireUserId(request);
        List<UserShop> bindings = userShopRepository.listByUserId(userId);
        List<UserShopResponse> out = new ArrayList<>(bindings.size());
        for (UserShop b : bindings) {
            Optional<ShopifyStoreAuth> auth = shopifyStoreAuthService.findActiveByShopName(b.getShopName());
            // Bound row exists but shopify_store_auth may be gone (uninstalled). Still show the row
            // so the user can see & unbind it; authStatus will be "MISSING".
            String authStatus = auth.map(a -> a.getStatus().name()).orElse("MISSING");
            java.time.Instant authorizedAt = auth.map(ShopifyStoreAuth::getAuthorizedAt).orElse(null);
            int productCount = thirdPlatformProductRepository.countByShop(b.getShopName());
            out.add(new UserShopResponse(
                    b.getShopName(),
                    b.getShopDomain(),
                    authStatus,
                    authorizedAt,
                    b.getBoundAt(),
                    productCount));
        }
        return out;
    }

    /**
     * Unbind a shop from the current user. Physical delete of the user_shop row;
     * the shopify_store_auth record is retained (can be re-bound without re-OAuth).
     * Returns 404 if the shop is not bound to this user.
     */
    @DeleteMapping("/{shopName}")
    public ResponseEntity<UnbindResponse> unbind(@PathVariable("shopName") String shopName,
                                                    HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("shopName is required", 400, "INVALID_SHOP_NAME");
        }
        int affected = userShopRepository.deleteByUserIdAndShopName(userId, shopName);
        if (affected == 0) {
            // Don't leak whether the shop exists at all — return 404 with a generic message.
            throw new CustomException("Shop not bound to current user", 404, "SHOP_NOT_BOUND");
        }
        log.info("Shop unbound userId={} shopName={}", userId, shopName);
        return ResponseEntity.ok(new UnbindResponse(shopName, true));
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            // Should never happen because JwtAuthFilter protects this path — defensive guard.
            throw new CustomException("User not authenticated", 401, "UNAUTHENTICATED");
        }
        return userId;
    }
}
