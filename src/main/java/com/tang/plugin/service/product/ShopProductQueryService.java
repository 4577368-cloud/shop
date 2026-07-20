package com.tang.plugin.service.product;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.product.ShopProductDetailVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductMediaRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Read APIs over the Shopify product mirror (Phase 1 detail drawer).
 */
@Slf4j
@Service
public class ShopProductQueryService {

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private ThirdPlatformProductMediaRepository thirdPlatformProductMediaRepository;

    public ShopProductDetailVO getDetail(String shopName, String itemId) {
        if (StringUtils.isAnyBlank(shopName, itemId)) {
            throw new CustomException("product detail requires shopName and itemId");
        }
        ThirdPlatformProduct product = thirdPlatformProductRepository
                .findActiveByShopAndItem(shopName, itemId)
                .orElseThrow(() -> new CustomException(
                        "product not found in mirror, shopName=" + shopName + ", itemId=" + itemId));

        return new ShopProductDetailVO()
                .setId(product.getId())
                .setShopName(product.getShopName())
                .setThirdPlatformItemId(product.getThirdPlatformItemId())
                .setHandle(product.getHandle())
                .setTitle(product.getTitle())
                .setDescription(product.getDescription())
                .setStatus(product.getStatus())
                .setCurrency(product.getCurrency())
                .setMinPrice(product.getMinPrice())
                .setMaxPrice(product.getMaxPrice())
                .setMinWeightGrams(product.getMinWeightGrams())
                .setMaxWeightGrams(product.getMaxWeightGrams())
                .setPrimaryImageUrl(product.getPrimaryImageUrl())
                .setUpdatedAt(product.getUpdatedAt())
                .setVariants(thirdPlatformSkuRepository.listByItem(shopName, itemId))
                .setMedia(thirdPlatformProductMediaRepository.listByItem(shopName, itemId));
    }
}
