package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class RemoveAcceptancesResult {
    private int removedCount;
    private List<LogisticsAcceptanceVO> acceptances = new ArrayList<>();
}
