package com.tang.plugin.domain.dto.match;

import com.tang.plugin.enums.match.MatchSource;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Scope for candidate generation. P1 MANUAL source produces zero machine candidates.
 */
@Data
@Accessors(chain = true)
public class GenerateMatchCandidateDTO {
    private String shopName;
    /** Optional platform SPU GID scope. */
    private String thirdPlatformItemId;
    /** Optional platform variant GID scope. */
    private String thirdPlatformSkuId;
    /** Which matcher to invoke; defaults to MANUAL when null. */
    private MatchSource matchSource;
}
