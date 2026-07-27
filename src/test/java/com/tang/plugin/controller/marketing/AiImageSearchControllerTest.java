package com.tang.plugin.controller.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import com.tang.plugin.repository.UserDailyUsageRepository;
import com.tang.plugin.repository.UserSubscriptionRepository;
import com.tang.plugin.service.auth.CookieHelper;
import com.tang.plugin.service.auth.JwtService;
import com.tang.plugin.service.billing.CreditService;
import com.tang.plugin.service.marketing.PipispyClient;
import com.tang.plugin.service.marketing.UpstreamPoolGuard;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 以图搜编排端点关键路径测试：
 *  - 成功：submit→status(done)→summary→product 编排返回，且整条只计一次费（U×2）。
 *  - 余额不足：返回 402 且不调用上游扣费。
 *  - 文件分支：进入托管逻辑，submit 失败时返回 JSON 错误（不抛裸异常）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiImageSearchControllerTest {

    private static final String SUBMIT_URI = "/v3/api/open/ai-search/image/submit/image-url";
    private static final String STATUS_URI = "/v3/api/open/ai-search/image/status";
    private static final String SUMMARY_URI = "/v3/api/open/ai-search/image/resultSummary";
    private static final String PRODUCT_URI = "/v3/api/open/ai-search/image/product/search";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private PipispyClient pipispyClient;
    @MockBean
    private CreditService creditService;
    @MockBean
    private UpstreamPoolGuard upstreamPoolGuard;
    @MockBean
    private UserDailyUsageRepository dailyUsageRepository;
    @MockBean
    private UserSubscriptionRepository subscriptionRepository;

    private MarketingDataResponse ok(String json, Integer consumed) throws Exception {
        return new MarketingDataResponse(true, "pipispy",
                objectMapper.readTree(json), consumed, null, 200, null, null, null, null);
    }

    private Cookie authCookieFor(Long uid) {
        String token = jwtService.generateAccessToken(uid, "test" + uid + "@example.com");
        return new Cookie(CookieHelper.ACCESS_COOKIE, token);
    }

    @Test
    void successOrchestratesAndChargesOnce() throws Exception {
        when(dailyUsageRepository.getTodayCount(any())).thenReturn(0);
        when(subscriptionRepository.findActiveByUser(any())).thenReturn(null);
        when(creditService.getBalance(any())).thenReturn(1000);
        when(pipispyClient.postData(eq(SUBMIT_URI), any()))
                .thenReturn(ok("{\"image_id\":\"img_abc\"}", null));
        when(pipispyClient.postData(eq(STATUS_URI), any()))
                .thenReturn(ok("{\"status\":\"done\"}", null));
        when(pipispyClient.postData(eq(SUMMARY_URI), any()))
                .thenReturn(ok("{}", null));
        // product/search 返回 4 条 → 上游 U=4 → 计 8
        when(pipispyClient.postData(eq(PRODUCT_URI), any()))
                .thenReturn(ok("{\"data\":[{\"id\":\"p1\",\"title\":\"A\",\"image\":\"u\",\"platform\":\"shopify\",\"usd_price\":9.9}],"
                        + "\"page\":{\"total_count\":4,\"page_count\":1,\"current_page\":1,\"page_size\":4,\"is_next\":false}}",
                        4));
        when(creditService.chargeMarketingCall(any(), eq(4), any(), any(), any(), any()))
                .thenReturn(new MarketingChargeResult(1L, 4, 8, "subscription", 992, 1L));

        mockMvc.perform(multipart("/api/plugin/marketing/ai-search-image")
                        .param("imageUrl", "https://example.com/x.jpg")
                        .param("pageSize", "4")
                        .cookie(authCookieFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value("p1"))
                .andExpect(jsonPath("$.chargedCredits").value(8))
                .andExpect(jsonPath("$.remainingUserCredits").value(992));

        // 关键：整条链路只计一次费（非 4 次 /data 调用）
        verify(creditService, times(1)).chargeMarketingCall(any(), eq(4), any(), any(), any(), any());
    }

    @Test
    void insufficientCreditsReturns402AndNoCharge() throws Exception {
        when(dailyUsageRepository.getTodayCount(any())).thenReturn(0);
        when(subscriptionRepository.findActiveByUser(any())).thenReturn(null);
        // 余额 3 < 预估 (4+3)*2 = 14
        when(creditService.getBalance(any())).thenReturn(3);

        mockMvc.perform(multipart("/api/plugin/marketing/ai-search-image")
                        .param("imageUrl", "https://example.com/x.jpg")
                        .param("pageSize", "4")
                        .cookie(authCookieFor(1L)))
                .andExpect(status().is(402));

        verify(pipispyClient, never()).postData(any(), any());
        verify(creditService, never()).chargeMarketingCall(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void fileUploadRejectedWhenPipispyUnreachableStillReturnsJson() throws Exception {
        when(dailyUsageRepository.getTodayCount(any())).thenReturn(0);
        when(subscriptionRepository.findActiveByUser(any())).thenReturn(null);
        when(creditService.getBalance(any())).thenReturn(1000);
        when(pipispyClient.postData(eq(SUBMIT_URI), any()))
                .thenReturn(new MarketingDataResponse(false, "pipispy", null, null, null, 502,
                        "submit failed", null, null, null));

        MockMultipartFile file = new MockMultipartFile("file", "cat.jpg", "image/jpeg", "img".getBytes());
        mockMvc.perform(multipart("/api/plugin/marketing/ai-search-image")
                        .file(file)
                        .param("pageSize", "4")
                        .cookie(authCookieFor(1L)))
                .andExpect(status().is(502));
    }
}
