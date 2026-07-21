package com.tang.plugin.service.skualign;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON helpers for {@code current_offer_scope_json} / {@code supplemental_offer_ids_json}. */
final class SkuOfferScopeHelper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SUPPLEMENT_OFFERS_V1 = 1;

    private SkuOfferScopeHelper() {
    }

    static String buildScopeJson(String primaryOfferId, String supplementOfferId) {
        List<Map<String, String>> scopes = new ArrayList<>();
        scopes.add(scopeEntry(primaryOfferId, "PRIMARY"));
        if (StringUtils.isNotBlank(supplementOfferId)) {
            scopes.add(scopeEntry(supplementOfferId.trim(), "SUPPLEMENT"));
        }
        try {
            return JSON.writeValueAsString(scopes);
        } catch (Exception e) {
            return "[{\"offerId\":\"" + primaryOfferId + "\",\"role\":\"PRIMARY\"}]";
        }
    }

    static String buildSupplementJson(String supplementOfferId) {
        if (StringUtils.isBlank(supplementOfferId)) {
            return null;
        }
        try {
            return JSON.writeValueAsString(List.of(scopeEntry(supplementOfferId.trim(), "SUPPLEMENT")));
        } catch (Exception e) {
            return "[{\"offerId\":\"" + supplementOfferId.trim() + "\",\"role\":\"SUPPLEMENT\"}]";
        }
    }

    static String parseSupplementOfferId(String supplementalOfferIdsJson) {
        if (StringUtils.isBlank(supplementalOfferIdsJson)) {
            return null;
        }
        try {
            List<Map<String, String>> entries = JSON.readValue(
                    supplementalOfferIdsJson, new TypeReference<>() {});
            for (Map<String, String> entry : entries) {
                if (entry == null) {
                    continue;
                }
                String role = entry.get("role");
                if (role == null || "SUPPLEMENT".equalsIgnoreCase(role)) {
                    String offerId = entry.get("offerId");
                    if (StringUtils.isNotBlank(offerId)) {
                        return offerId.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    static boolean hasSupplementOffer(String supplementalOfferIdsJson) {
        return StringUtils.isNotBlank(parseSupplementOfferId(supplementalOfferIdsJson));
    }

    static int maxSupplementOffersV1() {
        return MAX_SUPPLEMENT_OFFERS_V1;
    }

    private static Map<String, String> scopeEntry(String offerId, String role) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("offerId", offerId);
        m.put("role", role);
        return m;
    }
}
