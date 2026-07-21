package com.tang.plugin.domain.entity.skualign;

import com.tang.plugin.enums.skualign.AlignmentRunStatus;
import com.tang.plugin.enums.skualign.AlignmentTriggerType;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

@Data
@Accessors(chain = true)
public class AlignmentRun {
    private Long id;
    private String shopName;
    private AlignmentTriggerType triggerType;
    private String scopeType;
    private String scopeIdsJson;
    private String engineVersion;
    private AlignmentRunStatus runStatus;
    private Integer matchedCount;
    private Integer suggestedCount;
    private Integer unmappedCount;
    private Integer noSourceCount;
    private Integer blockedCount;
    private Integer failedCount;
    private Instant startedAt;
    private Instant finishedAt;
    private String errorSummary;
    private Integer delFlag;
    private Instant createdAt;
}
