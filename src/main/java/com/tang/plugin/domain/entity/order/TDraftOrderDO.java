package com.tang.plugin.domain.entity.order;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tang.plugin.enums.order.PluginOrderTypeEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Accessors(chain = true)
@TableName("t_draft_order")
public class TDraftOrderDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer status;
    private Instant payTime;
    private String payNo;
    private String channel;
    private BigDecimal purchaseAmount;
    private BigDecimal refundGoodsAmount;
    private String content;
    private String packageNo;
    private String language;
    private Long expireTime;
    private String email;
    private String country;
    private String name;
    private String expressNo;
    private String cancelReason;
    private Long cancelReasonId;
    private Integer delFlag;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
    private String countryId;
    private Integer overtimeFlag;
    private String crash;
    private PluginOrderTypeEnum type;
    private String shopName;
    private String outerOrderId;
    private String tradeNo;
}
