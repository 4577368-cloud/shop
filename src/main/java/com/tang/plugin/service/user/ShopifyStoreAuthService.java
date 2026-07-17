package com.tang.plugin.service.user;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.ShopifyAuthStatus;
import com.tang.plugin.repository.ShopifyStoreAuthRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class ShopifyStoreAuthService {

    @Resource
    private ShopifyStoreAuthRepository shopifyStoreAuthRepository;

    public Long saveActiveAuth(String shopName, String shopDomain, String accessToken, String scope) {
        if (StringUtils.isAnyBlank(shopName, shopDomain, accessToken)) {
            throw new CustomException("saveActiveAuth missing fields, shopDomain=" + shopDomain);
        }
        ShopifyStoreAuth auth = new ShopifyStoreAuth()
                .setShopName(shopName)
                .setShopDomain(shopDomain.toLowerCase())
                .setAccessToken(accessToken)
                .setScope(scope)
                .setStatus(ShopifyAuthStatus.ACTIVE)
                .setAuthorizedAt(Instant.now());
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
}
