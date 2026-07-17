package com.tang.plugin.domain.bo.product;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class PublicProductBO {
    private String shopName;
    private List<PublicProductConvertBO> requestList = new ArrayList<>();
}
