package com.tang.plugin.domain.entity.order;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.Instant;

@Data
@Accessors(chain = true)
@TableName("t_package_logistics_track")
public class TPackageLogisticsTrackDO implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long packageId;
    private String expressNo;
    private Instant trackTime;
    private String trackStatus;
    private String trackDesc;
    private String trackLocation;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
}
