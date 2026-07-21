package com.tang.plugin.domain.entity.skualign;

import com.tang.plugin.enums.skualign.ProductOrigin;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class ProductSourceBinding {
    private Long id;
    private String shopName;
    private String thirdPlatformItemId;
    private String primaryOfferId;
    private String primarySourceType;
    private String status;
    private String supplementalOfferIdsJson;
    private ProductOrigin productOrigin;
    private Integer matchedVariantsCount;
    private Integer totalVariantsCount;
    private Integer unresolvedVariantsCount;
    private Long lastAlignmentRunId;
    private Instant lastAlignedAt;
    private Integer delFlag;
    private Instant createdAt;
    private Instant updatedAt;
}
