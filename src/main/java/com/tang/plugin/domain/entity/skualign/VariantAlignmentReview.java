package com.tang.plugin.domain.entity.skualign;

import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantReviewState;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class VariantAlignmentReview {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String currentOfferScopeJson;
    private VariantReviewState reviewState;
    private String suggestedOfferId;
    private String suggestedOfferSkuId;
    private SourceRole suggestedSourceRole;
    private String suggestedMatchSource;
    private BigDecimal score;
    private ConfidenceLevel confidenceLevel;
    private String reasonCode;
    private String reasonText;
    private boolean requiresUserAction;
    private Long lastRunId;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
