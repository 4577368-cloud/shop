package com.tang.plugin.domain.entity.user;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 支付订单→积分发放幂等记录（B5 修复）。
 *
 * <p>同一个 PayPal 支付订单（payment_order_id）只允许发放一次积分。
 * 月订 / 加购包捕获成功后会写一条；webhook 自愈或前端重试再次进入时，
 * 先查此表，命中则直接返回已发放结果，绝不重复发分。
 *
 * <p>Table: payment_credit_grants（payment_order_id 为主键，PK 冲突即代表已发放）。
 */
@Data
@Accessors(chain = true)
public class PaymentCreditGrant {
    private Long paymentOrderId;
    private Long userId;
    /** subscription | credit_pack */
    private String kind;
    /** 套餐/包 code（sub_starter / pack_boost ...） */
    private String code;
    /** 实际发放积分 */
    private Integer grantedCredits;
    /** 发放后钱包余额 */
    private Integer balanceAfter;
    private Instant createdAt;
}
