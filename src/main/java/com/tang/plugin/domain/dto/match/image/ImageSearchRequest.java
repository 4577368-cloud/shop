package com.tang.plugin.domain.dto.match.image;

import lombok.Data;

/**
 * Request body for POST /api/plugin/match/image-search (A3-1 stateless preview).
 * {@code query} is reserved for backend text-correction but is not sent by the frontend in this step.
 */
@Data
public class ImageSearchRequest {
    private String shopName;
    private String thirdPlatformItemId;
    /** Number of candidates to fetch; defaults to 4 (1 primary + 3 alternatives) when null/<=0. */
    private Integer limit;
    /** Optional short subject word for image+text correction; reserved, unused by frontend in A3-1. */
    private String query;
}
