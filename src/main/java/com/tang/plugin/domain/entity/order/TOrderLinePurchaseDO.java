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
@TableName("t_order_line_purchase")
public class TOrderLinePurchaseDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long orderLineId;
    private Long orderId;
    private String itemNo;
    private String shopId;
    private String shopUrl;
    private String shopName;
    private BigDecimal purchaseAmount;
    private String skuId;
    private String goodsId;
    private String goodsName;
    private String goodsImg;
    private Integer nums;
    private BigDecimal price;
    private String providerType;
    private String dataSource;
    private BigDecimal discountAmount;
    private BigDecimal rate;
    private String thirdShopId;
    private String thirdGoodsId;
    private Integer delFlag;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
