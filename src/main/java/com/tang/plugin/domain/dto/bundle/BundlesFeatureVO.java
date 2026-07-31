package com.tang.plugin.domain.dto.bundle;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BundlesFeatureVO {
    private boolean eligibleForBundles;
    private String ineligibilityReason;
    private boolean sellsBundles;
}
