package com.tang.plugin.domain.dto.ranking;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One cleaned commodity row from the TikTok board export. Sent by the prep tool
 * and persisted as {@code rank_product}. Category splits are pre-computed by the
 * prep tool from the full category path.
 */
@Data
@Accessors(chain = true)
public class RankProductRowDTO {
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
}
