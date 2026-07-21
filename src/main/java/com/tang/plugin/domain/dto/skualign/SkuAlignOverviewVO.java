package com.tang.plugin.domain.dto.skualign;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class SkuAlignOverviewVO {
    private Integer totalProducts;
    private Integer totalVariants;
    private Integer unresolvedVariantsCount;
    private Integer suggestedCount;
    private Integer unmappedCount;
    private Integer noSourceCount;
    private Integer alignedProductsCount;
    private List<SkuAlignProductSummaryVO> items;
}
