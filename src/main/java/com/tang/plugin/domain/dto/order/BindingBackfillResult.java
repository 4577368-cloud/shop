package com.tang.plugin.domain.dto.order;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Result of a manual binding backfill. {@code matched} = candidate rows in scope;
 * {@code backfilled} = rows flipped UNBOUND→BOUND; {@code skippedAlreadyBound} = already-BOUND rows.
 */
@Data
@Accessors(chain = true)
public class BindingBackfillResult {
    private String shopName;
    private String variantGid;
    private int matched;
    private int backfilled;
    private int skippedAlreadyBound;
}
