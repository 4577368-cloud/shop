package com.tang.plugin.service.match;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.dto.match.GenerateMatchCandidateDTO;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.match.MatchStatus;
import com.tang.plugin.repository.ShopProductMatchCandidateRepository;
import com.tang.plugin.service.match.strategy.ProductMatchStrategy;
import com.tang.plugin.service.match.strategy.ProductMatchStrategyHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Candidate generation & lifecycle facade. Product Sync stays the upstream mirror source;
 * P1 does not auto-trigger generation. Zero candidates is a normal outcome (info log).
 */
@Slf4j
@Service
public class ProductMatchService {

    @Resource
    private ProductMatchStrategyHolder productMatchStrategyHolder;
    @Resource
    private ShopProductMatchCandidateRepository shopProductMatchCandidateRepository;
    @Resource
    private TxManger txManger;

    /**
     * Generate candidates for a scope via the resolved {@link MatchSource} strategy and persist them.
     *
     * @return number of candidates upserted.
     */
    public int generateCandidates(GenerateMatchCandidateDTO scope) {
        if (scope == null || StringUtils.isBlank(scope.getShopName())) {
            throw new CustomException("generateCandidates requires shopName");
        }
        MatchSource source = scope.getMatchSource() != null ? scope.getMatchSource() : MatchSource.MANUAL;
        scope.setMatchSource(source);

        ProductMatchStrategy strategy = productMatchStrategyHolder.get(source);
        List<ShopProductMatchCandidate> candidates = strategy.match(scope);
        if (candidates.isEmpty()) {
            log.info("generateCandidates produced 0 candidates shopName={} source={} itemId={}",
                    scope.getShopName(), source, scope.getThirdPlatformItemId());
            return 0;
        }
        for (ShopProductMatchCandidate candidate : candidates) {
            if (StringUtils.isAnyBlank(candidate.getThirdPlatformSkuId(), candidate.getTangbuySkuId())) {
                throw new CustomException("Candidate must be SKU-level (thirdPlatformSkuId + tangbuySkuId required), "
                        + "source=" + source + ", shopName=" + scope.getShopName());
            }
            if (candidate.getMatchSource() == null) {
                candidate.setMatchSource(source);
            }
        }
        txManger.run(() -> {
            for (ShopProductMatchCandidate candidate : candidates) {
                if (candidate.getStatus() == null) {
                    candidate.setStatus(MatchStatus.PENDING);
                }
                shopProductMatchCandidateRepository.upsert(candidate);
            }
        });
        log.info("generateCandidates upserted count={} shopName={} source={}",
                candidates.size(), scope.getShopName(), source);
        return candidates.size();
    }

    public List<ShopProductMatchCandidate> listPending(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("listPending requires shopName");
        }
        return shopProductMatchCandidateRepository.listByShopAndStatus(shopName, MatchStatus.PENDING);
    }

    /**
     * Reject a pending candidate. Validates shopName consistency; soft status update.
     */
    public void rejectCandidate(String shopName, Long candidateId) {
        ShopProductMatchCandidate candidate = loadAndCheckShop(shopName, candidateId);
        txManger.run(() -> shopProductMatchCandidateRepository.updateStatus(candidate.getId(), MatchStatus.REJECTED));
        log.info("Candidate rejected shopName={} candidateId={}", shopName, candidateId);
    }

    private ShopProductMatchCandidate loadAndCheckShop(String shopName, Long candidateId) {
        if (StringUtils.isBlank(shopName) || candidateId == null) {
            throw new CustomException("candidate operation requires shopName and candidateId");
        }
        ShopProductMatchCandidate candidate = shopProductMatchCandidateRepository.findById(candidateId)
                .orElseThrow(() -> new CustomException("Candidate not found, candidateId=" + candidateId));
        if (!shopName.equals(candidate.getShopName())) {
            throw new CustomException("Candidate shopName mismatch, candidateId=" + candidateId
                    + ", expected=" + shopName + ", actual=" + candidate.getShopName());
        }
        return candidate;
    }
}
