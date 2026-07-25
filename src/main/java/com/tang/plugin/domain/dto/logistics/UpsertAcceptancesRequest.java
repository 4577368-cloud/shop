package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量 UPSERT 物流接受决策请求。
 *
 * <p>对应前端 {@code upsertAcceptances(shopName, incoming)} 调用。
 * 以 (shop_name, third_platform_sku_id) 为自然键，命中则覆盖，未命中则插入。
 */
@Data
@Accessors(chain = true)
public class UpsertAcceptancesRequest {
    private String shopName;
    private List<LogisticsAcceptanceVO> acceptances = new ArrayList<>();
}
