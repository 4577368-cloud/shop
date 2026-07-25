package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * UPSERT 物流接受决策响应。
 *
 * <p>{@code upsertedCount} = 实际写入（插入或更新）的记录数；
 * {@code acceptances} = 写入后该 shop 的全量决策列表（与前端 readAcceptances 语义一致）。
 */
@Data
@Accessors(chain = true)
public class UpsertAcceptancesResult {
    private Integer upsertedCount;
    private List<LogisticsAcceptanceVO> acceptances = new ArrayList<>();
}
