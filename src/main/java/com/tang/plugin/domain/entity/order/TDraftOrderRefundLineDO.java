package com.tang.plugin.domain.entity.order;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
@TableName("t_draft_order_refund_line")
public class TDraftOrderRefundLineDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long refundId;
    private String refundNo;
    private Long orderId;
    private Long orderLineId;
    private Integer refundNum;
    private BigDecimal refundAmount;
    /** 1=待处理 8=成功 9=拒绝 */
    private Integer refundStatus;
    private Integer delFlag;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
