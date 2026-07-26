package com.tang.plugin.domain.entity.ranking;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single ranked commodity row inside a {@link RankSnapshot}. Numeric columns
 * mirror the TikTok board export; {@code categoryL1/L2/L3} are splits of the
 * full {@code categoryPath} for filtering.
 */
@Data
@Accessors(chain = true)
public class RankProduct {
    private Long id;
    private Long snapshotId;
    private String shopName;
    private Integer rankNo;
    private String productTitle;
    private String imageUrl;
    private String categoryL1;
    private String categoryL2;
    private String categoryL3;
    private String categoryPath;
    private BigDecimal priceUsd;
    private BigDecimal avgPriceUsd;
    private LocalDate listedAt;
    private Double rating;
    private Long salesVolume;
    private Double commissionRate;
    private BigDecimal gmvUsd;
    private Double gmvGrowthRate;
    private BigDecimal liveGmvUsd;
    private BigDecimal videoGmvUsd;
    private BigDecimal cardGmvUsd;
    private Integer creatorCount;
    private Double creatorOrderRate;
    private String tiktokUrl;
    private String country;
}
