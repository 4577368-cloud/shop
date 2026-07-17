package com.tang.plugin.domain.bo.product;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class DraftItemBO {
    private Long draftItemId;
}
