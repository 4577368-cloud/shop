package com.tang.plugin.domain.entity.product;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ProductAttribute {
    private String name;
    private String value;
    private Integer position;
    private String outerOptionId;
}
