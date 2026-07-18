package com.tang.plugin.domain.dto.match;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Confirm a pending candidate into an ACTIVE binding. shopName must match the candidate.
 */
@Data
@Accessors(chain = true)
public class ConfirmBindingDTO {
    private String shopName;
    private Long candidateId;
}
