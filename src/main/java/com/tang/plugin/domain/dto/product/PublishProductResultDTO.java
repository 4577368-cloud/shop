package com.tang.plugin.domain.dto.product;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PublishProductResultDTO {
    private boolean success;
    private String message;
    private String outerProductId;
}
