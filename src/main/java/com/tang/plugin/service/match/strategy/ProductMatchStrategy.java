package com.tang.plugin.service.match.strategy;

import com.tang.plugin.domain.dto.match.GenerateMatchCandidateDTO;
import com.tang.plugin.domain.entity.match.ShopProductMatchCandidate;
import com.tang.plugin.enums.match.MatchSource;

import java.util.List;

/**
 * SPI for producing match candidates. P1 ships only {@link ManualProductMatchStrategy};
 * RULE / IMAGE / AI implementations plug in later via {@link ProductMatchStrategyHolder}.
 */
public interface ProductMatchStrategy {

    MatchSource support();

    /**
     * Produce candidate rows for the given scope. May legitimately return an empty list.
     */
    List<ShopProductMatchCandidate> match(GenerateMatchCandidateDTO scope);
}
