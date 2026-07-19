package com.tang.plugin.service.publish;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.repository.ProductPublishRecordRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Idempotent publish-ledger orchestration; the persistence base for M1-4's real Shopify create.
 * getOrCreate is keyed by (shop_name, candidate_id); transitions delegate to guarded repository
 * updates so replays and concurrent triggers are safe. No Shopify calls here.
 */
@Slf4j
@Service
public class ProductPublishRecordService {

    private static final String DEFAULT_SHOP_TYPE = "SHOPIFY";
    private static final int ERROR_MESSAGE_MAX = 1024;

    @Resource
    private ProductPublishRecordRepository productPublishRecordRepository;

    /**
     * Return the existing ledger row for (shop, candidate) or create a PENDING one from the snapshot.
     * The snapshot fields are only used on first insert; an existing row is returned unchanged so the
     * publish history/state is never clobbered. Safe under races via the unique key.
     */
    public ProductPublishRecord getOrCreate(ProductPublishRecord snapshot) {
        if (snapshot == null || StringUtils.isBlank(snapshot.getShopName())
                || StringUtils.isBlank(snapshot.getCandidateId())) {
            throw new CustomException("publish record requires shopName and candidateId");
        }
        String shopName = snapshot.getShopName().trim();
        String candidateId = snapshot.getCandidateId().trim();

        return productPublishRecordRepository.findByShopAndCandidate(shopName, candidateId)
                .orElseGet(() -> insertPending(snapshot, shopName, candidateId));
    }

    private ProductPublishRecord insertPending(ProductPublishRecord snapshot, String shopName, String candidateId) {
        snapshot.setShopName(shopName);
        snapshot.setCandidateId(candidateId);
        if (StringUtils.isBlank(snapshot.getShopType())) {
            snapshot.setShopType(DEFAULT_SHOP_TYPE);
        }
        try {
            Long id = productPublishRecordRepository.insertPending(snapshot);
            return productPublishRecordRepository.findById(id).orElseThrow(
                    () -> new CustomException("publish record insert lost, shopName=" + shopName));
        } catch (DuplicateKeyException e) {
            // Concurrent insert won the unique key; return the row that landed first.
            log.info("Publish record concurrent insert, reusing existing shopName={} candidateId={}",
                    shopName, candidateId);
            return productPublishRecordRepository.findByShopAndCandidate(shopName, candidateId)
                    .orElseThrow(() -> new CustomException("publish record race unresolved, shopName=" + shopName));
        }
    }

    /** Count successfully published (listed) products for a shop — the "已刊登" metric. */
    public int countPublished(String shopName) {
        return productPublishRecordRepository.countPublishedByShop(shopName);
    }

    /**
     * Mark a real publish attempt as starting: PENDING/FAILED -> PUBLISHING, attempts += 1.
     * @return true if the transition applied; false when the row is already PUBLISHING/PUBLISHED.
     */
    public boolean markPublishing(Long id) {
        return productPublishRecordRepository.markPublishing(id) > 0;
    }

    /**
     * Mark success: PUBLISHING -> PUBLISHED with Shopify GIDs; idempotent (replay is a no-op) and
     * published_at is write-once. @return true if this call performed the transition.
     */
    public boolean markPublished(Long id, String shopifyProductId, String shopifyProductHandle,
                                 String shopifyVariantId, String shopifyInventoryItemId) {
        return productPublishRecordRepository.markPublished(
                id, shopifyProductId, shopifyProductHandle, shopifyVariantId, shopifyInventoryItemId) > 0;
    }

    /**
     * Mark failure: PUBLISHING -> FAILED, storing the (truncated) last error. Never overrides a
     * terminal PUBLISHED. @return true if this call performed the transition.
     */
    public boolean markFailed(Long id, String errorMessage) {
        return productPublishRecordRepository.markFailed(id, truncateError(errorMessage)) > 0;
    }

    private static String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() <= ERROR_MESSAGE_MAX
                ? errorMessage : errorMessage.substring(0, ERROR_MESSAGE_MAX);
    }
}
