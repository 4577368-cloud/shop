package com.tang.plugin.dto.marketing;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public final class MarketingDtos {

    private MarketingDtos() {}

    /** Frontend → plugin (key is injected server-side, never accepted from browser). */
    public record MarketingDataRequest(String uri, Map<String, Object> params, Integer expectedCredits) {}

    /** Plugin → frontend (aligns with shopify MarketingResponse). */
    public record MarketingDataResponse(
            boolean ok,
            String source,
            JsonNode data,
            Integer consumedCredits,
            Integer remainingCredits,
            Integer code,
            String message,
            Integer chargedCredits,
            Integer remainingUserCredits,
            Boolean freeWindow) {}

    /** Dossier fan-out: one pipispy call to batch. {@code tag} is the client-side key for the result. */
    public record DossierRequestItem(String tag, String uri, Map<String, Object> params, Integer expectedCredits) {}

    /** Dossier fan-out request: a batch of pipispy calls executed server-side in parallel. */
    public record DossierRequest(List<DossierRequestItem> requests) {}

    /** Dossier fan-out response: per-tag results + summed credit consumption. */
    public record DossierResponse(Map<String, MarketingDataResponse> results, int totalConsumedCredits) {}
}
