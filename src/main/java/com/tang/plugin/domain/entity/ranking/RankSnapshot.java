package com.tang.plugin.domain.entity.ranking;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One ranking snapshot = one import batch, uniquely identified by the displayed
 * date-range string for a shop. Future batches are distinguished by {@code dateRange}.
 */
@Data
@Accessors(chain = true)
public class RankSnapshot {
    private Long id;
    private String shopName;
    private String dateRange;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer productCount;
    private Instant createdAt;
    private Instant updatedAt;
    private Integer delFlag;
}
