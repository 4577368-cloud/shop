package com.tang.plugin.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tang.plugin.domain.entity.order.TDraftOrderLineDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TDraftOrderLineMapper extends BaseMapper<TDraftOrderLineDO> {
}
