package com.tang.plugin.mapper.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tang.plugin.domain.entity.order.TDraftOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TDraftOrderMapper extends BaseMapper<TDraftOrderDO> {
}
