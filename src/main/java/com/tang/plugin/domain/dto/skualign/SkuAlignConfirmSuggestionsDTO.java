package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class SkuAlignConfirmSuggestionsDTO {
    private String shopName;
    /** PAGE | PRODUCT | VARIANTS */
    private String targetScope;
    private List<String> productIds;
    private List<String> variantIds;
    private Long runId;
}
