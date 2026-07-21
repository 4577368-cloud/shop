package com.tang.plugin.service.match.sku;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.SkuBindDTO;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.enums.match.BindingStatus;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Manual per-variant SKU bind from the /sku-align picker. Idempotent rebind: deactivates any
 * existing PENDING/ACTIVE row for the variant, upserts a MANUAL candidate, then writes ACTIVE.
 */
@Slf4j
@Service
public class SkuManualBindService {

    private static final String BIND_SOURCE_MANUAL = "MANUAL";
    private static final String ALGO_MANUAL = "MANUAL";
    private static final int SCORE_SCALE = 4;

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private TxManger txManger;
    @Resource
    private OfferSkuMatrixValidator offerSkuMatrixValidator;

    public void bind(SkuBindDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getShopName())) {
            throw new CustomException("bind requires shopName");
        }
        if (StringUtils.isBlank(dto.getThirdPlatformSkuId())) {
            throw new CustomException("bind requires thirdPlatformSkuId");
        }
        if (StringUtils.isBlank(dto.getTangbuySkuId())) {
            throw new CustomException("bind requires tangbuySkuId");
        }
        if (StringUtils.isBlank(dto.getTangbuyProductId())) {
            throw new CustomException("bind requires tangbuyProductId");
        }

        String spec = StringUtils.trimToNull(dto.getTangbuySkuSpec());
        String detailUrl = StringUtils.trimToNull(dto.getDetailUrl());
        if (detailUrl == null) {
            detailUrl = "https://detail.1688.com/offer/" + dto.getTangbuyProductId() + ".html";
        }

        offerSkuMatrixValidator.assertSkuInOffer(dto.getTangbuyProductId(), dto.getTangbuySkuId());

        String reason = SkuMatchReason.encode(ALGO_MANUAL, 1.0d, dto.getTangbuySkuId(), spec, detailUrl);
        ShopProductMatchCandidate candidate = new ShopProductMatchCandidate()
                .setShopName(dto.getShopName())
                .setShopType(PluginType.SHOPIFY.getCode())
                .setThirdPlatformItemId(dto.getThirdPlatformItemId())
                .setThirdPlatformSkuId(dto.getThirdPlatformSkuId())
                .setTangbuyProductId(dto.getTangbuyProductId())
                .setTangbuySkuId(dto.getTangbuySkuId())
                .setMatchSource(MatchSource.MANUAL)
                .setMatchScore(BigDecimal.ONE.setScale(SCORE_SCALE, RoundingMode.HALF_UP))
                .setMatchReason(reason)
                .setStatus(MatchStatus.CONFIRMED);

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
            shopProductBindingRepository.deactivateBySkuId(dto.getShopName(), dto.getThirdPlatformSkuId());
            Long candidateId = shopProductMatchCandidateRepository.upsert(candidate);
            binding.setCandidateId(candidateId);
            shopProductBindingRepository.upsertActive(binding);
            log.info("SkuManualBind shopName={} thirdPlatformSkuId={} tangbuySkuId={} candidateId={}",
                    dto.getShopName(), dto.getThirdPlatformSkuId(), dto.getTangbuySkuId(), candidateId);
        });
    }
}
