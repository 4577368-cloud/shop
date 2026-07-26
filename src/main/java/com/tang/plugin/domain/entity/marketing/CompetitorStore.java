package com.tang.plugin.domain.entity.marketing;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 用户关注的竞店（运营中心 ☆ 收藏）。
 * Table: user_competitor_store
 */
@Data
@Accessors(chain = true)
public class CompetitorStore {
    private Long id;
    private Long userId;
    /** pipispy store id（13 字符 hex）。 */
    private String storeId;
    /** 店铺显示名（冗余存储，避免列表时再查）。 */
    private String storeName;
    private Instant createdAt;
    private Instant updatedAt;
}
