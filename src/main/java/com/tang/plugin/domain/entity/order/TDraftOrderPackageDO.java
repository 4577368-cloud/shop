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
@TableName("t_draft_order_package")
public class TDraftOrderPackageDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private String bzNo;
    private String packageNo;
    private Long lineId;
    private String lineName;
    private BigDecimal totalAmountPre;
    private BigDecimal totalAmountAct;
    private String expressNo;
    private String logistic;
    private String logisticId;
    private String deliveryTime;
    private Instant outboardTime;
    private String packageFeeContent;
    private String packageChoosedContent;
    private String comment;
    private BigDecimal fillAmount;
    private BigDecimal filledAmount;
    private BigDecimal refundAmount;
    private BigDecimal weight;
    private BigDecimal weightAct;
    private BigDecimal volume;
    private BigDecimal volumeAct;
    private Integer logisticsStatus;
    private String latestLogisticsInfo;
    private String channel;
    private String country;
    private Integer delFlag;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
