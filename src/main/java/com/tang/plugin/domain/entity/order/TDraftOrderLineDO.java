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
@TableName("t_draft_order_line")
public class TDraftOrderLineDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long userId;
    private String shopId;
    private String shopName;
    private String shopUrl;
    private Integer status;
    private BigDecimal purchaseAmount;
    private BigDecimal returnAmount;
    private BigDecimal postage;
    private String skuId;
    private String goodsAttribute;
    private String goodsId;
    private Integer goodsType;
    private String goodsUrl;
    private String goodsName;
    private String goodsImg;
    private Integer nums;
    private Integer stockNums;
    private Integer refundNums;
    private BigDecimal price;
    private BigDecimal discountAmount;
    private BigDecimal weight;
    private String outerLineId;
    private String outerVariantId;
    private Integer delFlag;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
