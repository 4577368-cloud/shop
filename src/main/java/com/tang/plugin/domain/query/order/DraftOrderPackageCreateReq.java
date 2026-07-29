package com.tang.plugin.domain.query.order;

import lombok.Data;

@Data
public class DraftOrderPackageCreateReq {
    private String packageComment;
    // lite: package line optional
    // @NotNull(message = "lineId not null")
    private Long lineId;
    private String lineName;
    private String deliveryTime;
    private OrderPackageChoosedContentReq packageChoosedContent;
}
