package com.tang.plugin.domain.bo.product;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PublicProductConvertBO {
    private String shopName;
    private DraftItemBO draftItemBO;
    private Object publishPayload;
}
