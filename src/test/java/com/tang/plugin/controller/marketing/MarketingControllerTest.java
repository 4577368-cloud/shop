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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tang.plugin.dto.billing.BillingDtos.CreditBucketBreakdown;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsRequest;
import com.tang.plugin.dto.billing.BillingDtos.GrantCreditsResult;
import com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult;
import com.tang.plugin.service.billing.CreditService;

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
    @Resource
    private CreditService creditService;

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

    // ===== B6 商业化关键路径测试：402 门禁 / 一次领取 / capture→lot 幂等 / FIFO 扣减 =====

    private Cookie authCookieFor(Long uid) {
        String token = jwtService.generateAccessToken(uid, "test" + uid + "@example.com");
        return new Cookie(CookieHelper.ACCESS_COOKIE, token);
    }

    @Test
    void dataWithInsufficientCreditsReturns402() throws Exception {
        // 全新用户（零余额）调用非免费端点：服务端门禁在调上游前返回 402（B4 + 402 门禁）。
        Long uid = 9100L;
        String body = "{\"uri\":\"/v3/api/open/store/list\",\"params\":{\"page\":1},\"expectedCredits\":5}";
        mockMvc.perform(post("/api/plugin/marketing/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(authCookieFor(uid)))
                .andExpect(status().is(402));
    }

    @Test
    void welcomeClaimIsIdempotent() throws Exception {
        // 欢迎分只能领取一次：首次 claimed=true，再次 alreadyClaimed=true，余额不重复增加（B3）。
        Long uid = 9200L;
        Cookie cookie = authCookieFor(uid);
        mockMvc.perform(post("/api/plugin/billing/credits/welcome/claim").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.alreadyClaimed").value(false))
                .andExpect(jsonPath("$.granted").value(30));

        mockMvc.perform(post("/api/plugin/billing/credits/welcome/claim").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(false))
                .andExpect(jsonPath("$.alreadyClaimed").value(true))
                .andExpect(jsonPath("$.balanceAfter").value(30));
    }

    @Test
    void captureGrantsLotIdempotently() {
        // capture→lot 幂等（B5）：同一 paymentOrderId 重复发放只产生一条批次与一条发放记录。
        Long uid = 9002L;
        Long orderId = 55501L;
        GrantCreditsResult first = creditService.grantSubscriptionCredits(uid, "sub_starter", orderId);
        GrantCreditsResult second = creditService.grantSubscriptionCredits(uid, "sub_starter", orderId);
        assertTrue(first.success());
        assertEquals(first.balanceAfter(), second.balanceAfter());

        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_credit_grants WHERE user_id = ? AND payment_order_id = ?",
                Integer.class, uid, orderId);
        Integer lots = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_lots WHERE user_id = ? AND source_type = 'sub_starter'",
                Integer.class, uid);
        assertEquals(Integer.valueOf(1), grants);
        assertEquals(Integer.valueOf(1), lots);
    }

    @Test
    void fifoConsumeDrainsPromoBeforeSubscription() {
        // FIFO 扣减顺序：promo 优先于 subscription（§4.3）。
        Long uid = 9003L;
        creditService.grantCredits(uid, new GrantCreditsRequest(5, "promo", null, null, "seed-promo"));
        creditService.grantCredits(uid, new GrantCreditsRequest(10, "subscription", null, null, "seed-sub"));
        // 实扣 = upstreamU × 2 = 12，跨越 promo(5) 与 subscription(10)。
        MarketingChargeResult charge = creditService.chargeMarketingCall(
                uid, 6, "/v3/api/open/store/list", "fifo-test-key", "endpoint", "ref");
        assertEquals("promo", charge.bucket());

        CreditBucketBreakdown bd = creditService.getBucketBreakdown(uid);
        assertEquals(0, bd.promoCredits());        // promo 被完全扣光
        assertEquals(3, bd.subscriptionCredits());  // subscription 仅扣 7，剩 3
    }

    @Test
    void dailyLimitUsesSubStarterPlanCode() throws Exception {
        // G2：活跃 sub_starter 日限应为 80，不是错误码 starter 掉到 5。
        Long uid = 9300L;
        Cookie cookie = authCookieFor(uid);
        creditService.grantSubscriptionCredits(uid, "sub_starter", 93001L);
        // 灌入今日已用 5 次：若错误落到 default=5 会立刻 429；正确 80 则仍可通过余额门禁到上游/其他错误。
        jdbcTemplate.update(
                "INSERT INTO user_daily_usage (user_id, usage_date, call_count) VALUES (?, CURRENT_DATE, 5)",
                uid);
        // 给足够余额避免 402
        creditService.grantCredits(uid, new GrantCreditsRequest(100, "manual", null, null, "seed"));
        String body = "{\"uri\":\"/v3/api/open/store/list\",\"params\":{\"page\":1},\"expectedCredits\":1}";
        // 不应因日限额 429（5/5）；可能因上游未配置返回 502/503，但绝不是 429
        mockMvc.perform(post("/api/plugin/marketing/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(cookie))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() != 429,
                        "sub_starter must not hit default daily limit of 5"));
    }

    @Test
    void clientExpectedCreditsCannotUndercutServerFloor() throws Exception {
        // G4：客户端 expectedCredits=1 不能压低服务端 pageSize×2 下界 → 仍 402。
        Long uid = 9400L;
        Cookie cookie = authCookieFor(uid);
        // 余额 10 < 默认 pageSize 12 × 2 = 24
        creditService.grantCredits(uid, new GrantCreditsRequest(10, "manual", null, null, "seed"));
        String body = "{\"uri\":\"/v3/api/open/store/list\",\"params\":{},\"expectedCredits\":1}";
        mockMvc.perform(post("/api/plugin/marketing/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .cookie(cookie))
                .andExpect(status().is(402));
    }

    @Test
    void welcomeStatusReflectsClaim() throws Exception {
        Long uid = 9500L;
        Cookie cookie = authCookieFor(uid);
        mockMvc.perform(get("/api/plugin/billing/credits/welcome/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(false));
        mockMvc.perform(post("/api/plugin/billing/credits/welcome/claim").cookie(cookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/plugin/billing/credits/welcome/status").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimed").value(true));
    }
}
