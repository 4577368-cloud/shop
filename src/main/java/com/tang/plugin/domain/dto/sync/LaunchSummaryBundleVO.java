package com.tang.plugin.domain.dto.sync;

import com.tang.plugin.domain.dto.logistics.LogisticsAnalysisVO;
import com.tang.plugin.domain.dto.match.ImageBindingView;
import com.tang.plugin.domain.dto.pricing.PricingTemplateVO;
import com.tang.plugin.domain.dto.skualign.SkuAlignOverviewVO;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * Raw inputs for the workbench sync ceremony page — one round trip instead of six.
 */
@Data
@Accessors(chain = true)
public class LaunchSummaryBundleVO {
    private String shopName;
    private List<ThirdPlatformProduct> shopProducts;
    private List<ImageBindingView> bindings;
    private SkuAlignOverviewVO skuOverview;
    private LogisticsAnalysisVO logisticsAnalysis;
    private PricingTemplateVO pricingTemplate;
    /**
     * 商品状态分组计数（ACTIVE / DRAFT / ARCHIVED / UNKNOWN）。
     * 用于 sync 报告真实统计：发布上架、操作到草稿、操作下架。
     */
    private Map<String, Integer> productStatusCounts;
}
