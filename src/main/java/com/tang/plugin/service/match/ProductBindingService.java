package com.tang.plugin.service.match;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.ConfirmBindingDTO;
import com.tang.plugin.domain.dto.match.ManualBindingDTO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Binding write facade. All writes run inside {@link TxManger#run}; rebind deactivates the old
 * ACTIVE binding before writing the new one. Binding is SKU-level only (P1).
 */
@Slf4j
@Service
public class ProductBindingService {

    private static final String BIND_SOURCE_MANUAL = "MANUAL";
    private static final String BIND_SOURCE_FROM_CANDIDATE = "FROM_CANDIDATE";

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private TxManger txManger;

    /**
     * Confirm a pending candidate into an ACTIVE binding. Requires SKU-level candidate.
     */
    public void confirmBinding(ConfirmBindingDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName()) || dto.getCandidateId() == null) {
            throw new CustomException("confirmBinding requires shopName and candidateId");
        }
        ShopProductMatchCandidate candidate = shopProductMatchCandidateRepository.findById(dto.getCandidateId())
                .orElseThrow(() -> new CustomException("Candidate not found, candidateId=" + dto.getCandidateId()));
        if (!dto.getShopName().equals(candidate.getShopName())) {
            throw new CustomException("Candidate shopName mismatch, candidateId=" + dto.getCandidateId());
        }
        if (StringUtils.isAnyBlank(candidate.getThirdPlatformSkuId(), candidate.getTangbuySkuId())) {
            throw new CustomException("Candidate is not SKU-level, cannot bind, candidateId=" + dto.getCandidateId());
        }

        ShopProductBinding binding = new ShopProductBinding()
                .setShopName(candidate.getShopName())
                .setShopType(StringUtils.defaultIfBlank(candidate.getShopType(), PluginType.SHOPIFY.getCode()))
                .setThirdPlatformItemId(candidate.getThirdPlatformItemId())
                .setThirdPlatformSkuId(candidate.getThirdPlatformSkuId())
                .setTangbuyProductId(candidate.getTangbuyProductId())
                .setTangbuySkuId(candidate.getTangbuySkuId())
                .setBindSource(BIND_SOURCE_FROM_CANDIDATE)
                .setCandidateId(candidate.getId())
                .setBindStatus(BindingStatus.ACTIVE);

        txManger.run(() -> {
            String oldTangbuySkuId = currentBoundTangbuySkuId(binding.getShopName(), binding.getThirdPlatformSkuId());
            shopProductMatchCandidateRepository.updateStatus(candidate.getId(), MatchStatus.CONFIRMED);
            shopProductBindingRepository.deactivateBySkuId(binding.getShopName(), binding.getThirdPlatformSkuId());
            shopProductBindingRepository.upsertActive(binding);
            logRebind(binding.getShopName(), binding.getThirdPlatformSkuId(), oldTangbuySkuId,
                    binding.getTangbuySkuId(), "confirmBinding candidateId=" + candidate.getId());
        });
    }

    /**
     * Manual SKU-level binding. thirdPlatformSkuId is required (no SPU-only binding in P1).
     */
    public void bindManually(ManualBindingDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("bindManually requires shopName");
        }
        if (StringUtils.isBlank(dto.getThirdPlatformSkuId())) {
            throw new CustomException("bindManually requires thirdPlatformSkuId (SKU-level only)");
        }
        if (StringUtils.isBlank(dto.getTangbuySkuId())) {
            throw new CustomException("bindManually requires tangbuySkuId");
        }

        ShopProductBinding binding = new ShopProductBinding()
                .setShopName(dto.getShopName())
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                .setThirdPlatformSkuId(dto.getThirdPlatformSkuId())
                .setTangbuyProductId(dto.getTangbuyProductId())
                .setTangbuySkuId(dto.getTangbuySkuId())
                .setBindSource(BIND_SOURCE_MANUAL)
                .setBindStatus(BindingStatus.ACTIVE);

        txManger.run(() -> {
            String oldTangbuySkuId = currentBoundTangbuySkuId(binding.getShopName(), binding.getThirdPlatformSkuId());
            shopProductBindingRepository.deactivateBySkuId(binding.getShopName(), binding.getThirdPlatformSkuId());
            shopProductBindingRepository.upsertActive(binding);
            logRebind(binding.getShopName(), binding.getThirdPlatformSkuId(), oldTangbuySkuId,
                    binding.getTangbuySkuId(), "bindManually");
        });
    }

    /**
     * Unbind (soft) the ACTIVE binding of a SKU. Validates shopName scope.
     */
    public void unbind(String shopName, String thirdPlatformSkuId) {
        if (StringUtils.isAnyBlank(shopName, thirdPlatformSkuId)) {
            throw new CustomException("unbind requires shopName and thirdPlatformSkuId");
        }
        shopProductBindingRepository.findActiveBySkuId(shopName, thirdPlatformSkuId)
                .orElseThrow(() -> new CustomException("No active binding to unbind, shopName=" + shopName
                        + ", thirdPlatformSkuId=" + thirdPlatformSkuId));
        txManger.run(() -> shopProductBindingRepository.deactivateBySkuId(shopName, thirdPlatformSkuId));
        log.info("Binding unbound shopName={} thirdPlatformSkuId={}", shopName, thirdPlatformSkuId);
    }

    private String currentBoundTangbuySkuId(String shopName, String thirdPlatformSkuId) {
        return shopProductBindingRepository.findActiveBySkuId(shopName, thirdPlatformSkuId)
                .map(ShopProductBinding::getTangbuySkuId)
                .orElse(null);
    }

    private void logRebind(String shopName, String thirdPlatformSkuId,
                           String oldTangbuySkuId, String newTangbuySkuId, String via) {
        boolean rebind = oldTangbuySkuId != null && !oldTangbuySkuId.equals(newTangbuySkuId);
        log.info("Binding {} shopName={} thirdPlatformSkuId={} tangbuySkuId: {} -> {} via={}",
                rebind ? "REBOUND" : "SET",
                shopName, thirdPlatformSkuId, oldTangbuySkuId, newTangbuySkuId, via);
    }
}
