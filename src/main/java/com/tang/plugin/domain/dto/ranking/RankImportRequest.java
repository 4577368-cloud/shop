package com.tang.plugin.domain.dto.ranking;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for {@code POST /api/plugin/ranking/import}. The snapshot is
 * identified by {@code dateRange} (the displayed date window); products carry
 * their own cleaned fields. {@code startDate}/{@code endDate} are derived from
 * {@code dateRange} and used for sorting/snapshot selection.
 */
@Data
@Accessors(chain = true)
public class RankImportRequest {
    private String dateRange;
    private String country;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<RankProductRowDTO> products;
}
