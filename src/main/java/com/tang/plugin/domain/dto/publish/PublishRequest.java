package com.tang.plugin.domain.dto.publish;

import lombok.Data;

/** Request body for POST /api/plugin/catalog/publish. Single candidate, no batch. */
@Data
public class PublishRequest {
    private String shopName;
    private String candidateId;
}
