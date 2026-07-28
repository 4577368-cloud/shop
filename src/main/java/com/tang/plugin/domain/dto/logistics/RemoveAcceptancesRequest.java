package com.tang.plugin.domain.dto.logistics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RemoveAcceptancesRequest {
    private String shopName;
    private List<String> skuIds = new ArrayList<>();
}
