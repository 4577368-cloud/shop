package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 用户订阅实例（§5）。月订激活后写一条；到期清零由定时任务负责。
 * Table: user_subscriptions
 */
@Data
@Accessors(chain = true)
public class UserSubscription {
    private Long id;
    private Long userId;
    private String planCode;
    private Long paymentOrderId;
    private String status;       // active / cancelled / expired
    private Integer creditsGranted;
    private Instant startedAt;
    private Instant endsAt;
    private Instant createdAt;
    private Instant updatedAt;
}
