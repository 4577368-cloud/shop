package com.tang.plugin.dto.marketing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public final class MarketingDtos {

    private MarketingDtos() {}

    /** Frontend → plugin (key is injected server-side, never accepted from browser). */
    public record MarketingDataRequest(String uri, Map<String, Object> params) {}

    /** Plugin → frontend (aligns with shopify MarketingResponse). */
    public record MarketingDataResponse(
            boolean ok,
            String source,
            JsonNode data,
            Integer consumedCredits,
            Integer remainingCredits,
            Integer code,
            String message) {}
}
