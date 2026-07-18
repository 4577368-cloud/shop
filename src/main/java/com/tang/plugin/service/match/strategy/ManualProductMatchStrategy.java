package com.tang.plugin.service.match.strategy;

import com.tang.plugin.domain.dto.match.GenerateMatchCandidateDTO;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.match.MatchSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * P1 manual strategy: produces no machine candidates. Manual bindings are created
 * directly via ProductBindingService. Zero candidates here is expected (info log, not error).
 */
@Slf4j
@Component
public class ManualProductMatchStrategy implements ProductMatchStrategy {

    @Override
    public MatchSource support() {
        return MatchSource.MANUAL;
    }

    @Override
    public List<ShopProductMatchCandidate> match(GenerateMatchCandidateDTO scope) {
        log.info("ManualProductMatchStrategy produces no auto candidates shopName={} itemId={} skuId={}",
                scope.getShopName(), scope.getThirdPlatformItemId(), scope.getThirdPlatformSkuId());
        return Collections.emptyList();
    }
}
