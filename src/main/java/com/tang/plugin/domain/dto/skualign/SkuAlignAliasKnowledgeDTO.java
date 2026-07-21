package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SkuAlignAliasKnowledgeDTO {
    private String shopName;
    private String sourceText;
    private String targetText;
    private String categoryHint;
    private String derivedFrom;
}
