package com.tang.plugin.service.marketing;

import com.tang.plugin.domain.entity.marketing.CompetitorStore;
import com.tang.plugin.repository.CompetitorStoreRepository;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CompetitorStoreService {

    @Resource
    private CompetitorStoreRepository competitorStoreRepository;

    public List<CompetitorStore> listByUser(Long userId) {
        return competitorStoreRepository.listByUserId(userId);
    }

    public CompetitorStore toggle(Long userId, String storeId, String storeName) {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("storeId is required");
        }
        var existing = competitorStoreRepository.findByUserIdAndStoreId(userId, storeId);
        if (existing.isPresent()) {
            competitorStoreRepository.deleteByUserIdAndStoreId(userId, storeId);
            return null; // removed
        }
        return competitorStoreRepository.upsert(userId, storeId, storeName);
    }
}
