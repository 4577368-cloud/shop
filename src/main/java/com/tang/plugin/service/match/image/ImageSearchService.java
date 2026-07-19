package com.tang.plugin.service.match.image;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates A3-1 stateless image-search preview: resolve a mirrored shop product's primary image,
 * then query 1688. Read-only — no persistence, no candidate/binding writes (those are A3-2).
 */
@Slf4j
@Service
public class ImageSearchService {

    /** Error message prefix: the shop product has no usable primary image to search with. */
    public static final String ERR_NO_PRIMARY_IMAGE = "NO_PRIMARY_IMAGE";
    /** Error message prefix: no mirrored shop product matches (shopName, thirdPlatformItemId). */
    public static final String ERR_PRODUCT_NOT_FOUND = "PRODUCT_NOT_FOUND";

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private Newton1688ImageSearchClient newton1688ImageSearchClient;

    /**
     * Search 1688 by the primary image of a mirrored shop product.
     *
     * @param shopName            shop identifier
     * @param thirdPlatformItemId the mirrored SPU id (Shopify product id in the mirror)
     * @param limit               candidates to fetch (null/&lt;=0 → client default 4)
     * @param query               reserved short subject word (unused by frontend in A3-1)
     */
    public List<ImageSearchProductVO> searchByShopProduct(String shopName, String thirdPlatformItemId,
                                                          Integer limit, String query) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformItemId)) {
            throw new CustomException("image search requires shopName and thirdPlatformItemId");
        }
        String imageUrl = resolvePrimaryImageUrl(shopName, thirdPlatformItemId);
        return newton1688ImageSearchClient.search(imageUrl, limit, query);
    }

    private String resolvePrimaryImageUrl(String shopName, String thirdPlatformItemId) {
        ThirdPlatformProduct product = thirdPlatformProductRepository.listByShop(shopName).stream()
                .filter(p -> thirdPlatformItemId.equals(p.getThirdPlatformItemId()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ERR_PRODUCT_NOT_FOUND
                        + ": 未找到店铺商品(" + shopName + "/" + thirdPlatformItemId + ")，请先同步商品"));
        String imageUrl = product.getPrimaryImageUrl();
        if (StringUtils.isBlank(imageUrl)) {
            throw new CustomException(ERR_NO_PRIMARY_IMAGE
                    + ": 该商品无主图，无法进行 1688 图搜");
        }
        return imageUrl;
    }
}
