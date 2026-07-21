package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.List;

@Data
@Accessors(chain = true)
public class MatchJobProgressVO {
    private Long jobId;
    private String shopName;
    private String jobType;
    private String jobStatus;
    private int total;
    private int processed;
    private int linked;
    private int skipped;
    private int failed;
    private int percent;
    private String lastError;
    private Instant startedAt;
    private Instant finishedAt;
    private List<String> recent;
}
