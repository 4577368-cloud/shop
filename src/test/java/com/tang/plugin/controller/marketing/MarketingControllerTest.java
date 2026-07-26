package com.tang.plugin.controller.marketing;

import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.auth.JwtService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MarketingController smoke tests: URI whitelist, dossier size limit, reference enums,
 * and competitor watchlist CRUD.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarketingControllerTest {

    private static final Long TEST_USER_ID = 1001L;

    @Autowired
    private MockMvc mockMvc;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private JwtService jwtService;

    private Cookie authCookie;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM user_competitor_store WHERE user_id = ?", TEST_USER_ID);
        String token = jwtService.generateAccessToken(TEST_USER_ID, "test@example.com");
        authCookie = new Cookie(CookieHelper.ACCESS_COOKIE, token);
    }

    @Test
    void referenceEnumsReturnsStaticData() throws Exception {
        mockMvc.perform(get("/api/plugin/marketing/reference/enums")
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").isArray())
                .andExpect(jsonPath("$.productCategory").isArray())
                .andExpect(jsonPath("$.shopType").isArray())
                .andExpect(jsonPath("$.cta").isArray());
    }

    @Test
    void dataWithBlockedUriReturns403() throws Exception {
        String body = """
                {"uri":"/v3/api/open/admin/delete-all","params":{}}
                """;
        mockMvc.perform(post("/api/plugin/marketing/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void dossierWithTooManyItemsReturns400() throws Exception {
        StringBuilder sb = new StringBuilder("{\"requests\":[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"uri\":\"/v3/api/open/store/list\",\"tag\":\"t").append(i).append("\"}");
        }
        sb.append("]}");
        mockMvc.perform(post("/api/plugin/marketing/dossier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString())
                        .cookie(authCookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listCompetitorsWhenEmptyReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/api/plugin/marketing/competitors")
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void toggleCompetitorAddsAndRemoves() throws Exception {
        String body = "{\"storeId\":\"store-abc\",\"storeName\":\"Test Store\"}";

        // add
        mockMvc.perform(post("/api/plugin/marketing/competitors/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(true))
                .andExpect(jsonPath("$.id").value("store-abc"));

        // list should contain it
        mockMvc.perform(get("/api/plugin/marketing/competitors")
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("store-abc"));

        // remove
        mockMvc.perform(post("/api/plugin/marketing/competitors/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(false));

        // list should be empty again
        mockMvc.perform(get("/api/plugin/marketing/competitors")
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void toggleCompetitorWithoutStoreIdReturns400() throws Exception {
        String body = "{\"storeName\":\"Test\"}";
        mockMvc.perform(post("/api/plugin/marketing/competitors/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authCookie))
                .andExpect(status().isBadRequest());
    }
}
