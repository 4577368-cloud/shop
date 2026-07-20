package com.tang.plugin.domain.dto.logistics;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class MarketSelectionDTO {
    private String marketGroupId;
    private List<String> countryCodes = new ArrayList<>();
}
