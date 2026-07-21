package com.tang.plugin.domain.entity.skualign;

import com.tang.plugin.enums.match.MatchSource;
import com.tang.plugin.enums.skualign.ConfidenceLevel;
import com.tang.plugin.enums.skualign.SourceRole;
import com.tang.plugin.enums.skualign.VariantBindingState;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
public class VariantSkuBinding {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String thirdPlatformSkuId;
    private String offerId;
    private String offerSkuId;
    private SourceRole sourceRole;
    private MatchSource matchSource;
    private VariantBindingState bindingState;
    private BigDecimal confidenceScore;
    private ConfidenceLevel confidenceLevel;
    private String explanationJson;
    private boolean manualLocked;
    private boolean active;
    private String createdByType;
    private String createdById;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
