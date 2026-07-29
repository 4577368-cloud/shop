package com.tang.plugin.domain.vo.order;

import com.tang.plugin.domain.entity.order.TDraftOrderPackageDO;
import com.tang.plugin.domain.entity.order.TPackageLogisticsTrackDO;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class DraftOrderPackageDetailVO {
    private TDraftOrderPackageDO orderPackage;
    private List<TPackageLogisticsTrackDO> tracks = new ArrayList<>();
}
