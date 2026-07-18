package com.tang.plugin.service.match;

import com.tang.plugin.domain.dto.match.SkuBindingView;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.repository.ShopProductBindingRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Read-only binding lookup for the order side. P1 does not modify order code — this service
 * is the reserved wiring point: ExternalOrderLine.outerVariantId → active binding target.
 */
@Slf4j
@Service
public class ProductBindingQueryService {

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;

    /**
     * Find the ACTIVE Tangbuy target for a platform variant GID. Empty is a normal miss.
     */
    public Optional<SkuBindingView> findActiveSkuBinding(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            return Optional.empty();
        }
        Optional<SkuBindingView> view = shopProductBindingRepository
                .findActiveBySkuId(shopName, thirdPlatformSkuId)
                .map(this::toView);
        if (view.isEmpty()) {
            log.info("No active binding shopName={} thirdPlatformSkuId={}", shopName, thirdPlatformSkuId);
        }
        return view;
    }

    private SkuBindingView toView(ShopProductBinding binding) {
        return new SkuBindingView()
                .setShopName(binding.getShopName())
                .setThirdPlatformItemId(binding.getThirdPlatformItemId())
                .setThirdPlatformSkuId(binding.getThirdPlatformSkuId())
                .setTangbuyProductId(binding.getTangbuyProductId())
                .setTangbuySkuId(binding.getTangbuySkuId());
    }
}
