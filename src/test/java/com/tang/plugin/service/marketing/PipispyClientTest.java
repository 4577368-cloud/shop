package com.tang.plugin.service.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.config.PipispyProperties;
import com.tang.plugin.dto.marketing.MarketingDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PipispyClient logic that does not require real HTTP calls.
 */
class PipispyClientTest {

    private PipispyClient client;
    private PipispyProperties props;

    @BeforeEach
    void setUp() {
        props = new PipispyProperties();
        props.setApiKey(""); // not configured
        props.setDataUrl("https://example.com/data");
        props.setCreditsUrl("https://example.com/credits");
        client = new PipispyClient(props, new ObjectMapper());
    }

    @Test
    void postDataWhenNotConfiguredReturns503() {
        var res = client.postData("/v3/api/open/store/list", Map.of());
        assertFalse(res.ok());
        assertEquals(503, res.code());
        assertTrue(res.message().contains("not configured"));
    }

    @Test
    void fetchCreditsBalanceWhenNotConfiguredReturns503() {
        var res = client.fetchCreditsBalance();
        assertFalse(res.ok());
        assertEquals(503, res.code());
    }

    @Test
    void fanOutWhenNotConfiguredReturnsErrorForEachItem() {
        var items = List.of(
                new MarketingDtos.DossierRequestItem("a", "/v3/api/open/store/list", Map.of(), null),
                new MarketingDtos.DossierRequestItem("b", "/v3/api/open/store/list", Map.of(), null)
        );
        Map<String, com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse> out = client.fanOut(items);
        assertEquals(2, out.size());
        assertFalse(out.get("a").ok());
        assertFalse(out.get("b").ok());
    }

    @Test
    void fanOutWithEmptyListReturnsEmptyMap() {
        var out = client.fanOut(List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void fanOutWithNullListReturnsEmptyMap() {
        var out = client.fanOut(null);
        assertTrue(out.isEmpty());
    }
}
