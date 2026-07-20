package com.tang.plugin.service.product;

import com.tang.plugin.config.TxManger;
import com.tang.plugin.repository.ThirdPlatformProductMediaRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import com.tang.plugin.repository.ThirdPlatformSkuRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Soft-deletes a mirrored Shopify product (SPU + SKUs + media) in one transaction.
 */
@Slf4j
@Service
public class ProductMirrorDeleteService {

    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ThirdPlatformSkuRepository thirdPlatformSkuRepository;
    @Resource
    private ThirdPlatformProductMediaRepository thirdPlatformProductMediaRepository;
    @Resource
    private TxManger txManger;

    /**
     * @return true when the SPU row was soft-deleted (was active); false if already gone / missing.
     */
    public boolean softDeleteCascade(String shopName, String productGid) {
        if (StringUtils.isAnyBlank(shopName, productGid)) {
            return false;
        }
        boolean[] deleted = {false};
        txManger.run(() -> {
            int n = thirdPlatformProductRepository.softDelete(shopName, productGid);
            thirdPlatformSkuRepository.softDeleteByItem(shopName, productGid);
            thirdPlatformProductMediaRepository.softDeleteByItem(shopName, productGid);
            deleted[0] = n > 0;
        });
        if (deleted[0]) {
            log.info("Product mirror soft-deleted shopName={} productId={}", shopName, productGid);
        } else {
            log.info("Product mirror soft-delete no-op (already gone) shopName={} productId={}",
                    shopName, productGid);
        }
        return deleted[0];
    }
}
