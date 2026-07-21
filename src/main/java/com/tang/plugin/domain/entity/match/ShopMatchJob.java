package com.tang.plugin.domain.entity.match;

import com.tang.plugin.enums.match.MatchJobStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class ShopMatchJob {
    private Long id;
    private String shopName;
    private String jobType;
    private MatchJobStatus status;
    /** When set, only this thirdPlatformItemId is (re)matched. */
    private String scopeItemId;
    private int totalCount;
    private int processedCount;
    private int linkedCount;
    private int skippedCount;
    private int failedCount;
    private String lastError;
    private String recentJson;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private int delFlag;
}
