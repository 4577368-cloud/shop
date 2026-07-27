package com.tang.plugin.controller.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataRequest;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierRequest;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierRequestItem;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierResponse;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import com.tang.plugin.service.marketing.CompetitorStoreService;
import com.tang.plugin.service.marketing.PipispyClient;
import com.tang.plugin.service.billing.CreditService;
import com.tang.plugin.repository.CreditTransactionRepository;
import com.tang.plugin.repository.UserDailyUsageRepository;
import com.tang.plugin.repository.UserSubscriptionRepository;
import com.tang.plugin.domain.entity.user.UserSubscription;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * pipispy marketing proxy for the operations center.
 *
 * <p>Paths under {@code /api/plugin/marketing/**} require JWT ({@link com.tang.plugin.config.JwtAuthFilter}).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/marketing")
@RequiredArgsConstructor
public class MarketingController {

    /** 允许调用的 pipispy URI 白名单（前缀匹配）。防止恶意构造请求访问任意接口。 */
    private static final Set<String> ALLOWED_URI_PREFIXES = Set.of(
            "/v3/api/open/store/detail/competition",
            "/v3/api/open/store/detail/competition/products",
            "/v3/api/open/rank/ad-product/list",
            "/v3/api/open/ppspy/ad-products/search",
            "/v3/api/open/ppspy/ad-products/detail",
            "/v3/api/open/tiktok-shop/shop/list",
            "/v3/api/open/tiktok-shop/shop/detail",
            "/v3/api/open/ai-search/image/submit",
            "/v3/api/open/ai-search/image/status",
            "/v3/api/open/ai-search/image/resultSummary",
            "/v3/api/open/adspy/list",
            "/v3/api/open/ad-library/ads",
            "/v3/api/open/store/list",
            "/v3/api/open/store/ad-trend",
            "/v3/api/open/store/longest-run-ads",
            "/v3/api/open/store/most-used-ads",
            "/v3/api/open/store/fb-pages",
            "/v3/api/open/store/data-analysis",
            "/v3/api/open/store/region-analysis",
            "/v3/api/open/store/delivery-analysis"
    );
    private static final int MAX_DOSSIER_ITEMS = 20;

    private final PipispyClient pipispyClient;
    private final ObjectMapper objectMapper;
    private final CompetitorStoreService competitorStoreService;
    private final CreditService creditService;
    private final CreditTransactionRepository txnRepository;
    private final UserDailyUsageRepository dailyUsageRepository;
    private final UserSubscriptionRepository subscriptionRepository;

  @PostMapping("/data")
  public ResponseEntity<MarketingDataResponse> postData(
      HttpServletRequest httpRequest,
      @RequestBody MarketingDataRequest body) {
    assertAllowedUri(body.uri());
    Long userId = currentUserId(httpRequest);
    return toHttp(handleMarketing(userId, body.uri(), body.params(), body.expectedCredits()));
  }

  /**
   * GET variant for dev tunnels that strip POST bodies. {@code params} is URL-encoded JSON object.
   */
  @GetMapping("/data")
  public ResponseEntity<MarketingDataResponse> getData(
      HttpServletRequest httpRequest,
      @RequestParam String uri,
      @RequestParam(required = false) String params,
      @RequestParam(required = false) Integer expectedCredits) {
    assertAllowedUri(uri);
    Long userId = currentUserId(httpRequest);
    Map<String, Object> map = parseParams(params);
    return toHttp(handleMarketing(userId, uri, map, expectedCredits));
  }

  @GetMapping("/credits-balance")
  public ResponseEntity<MarketingDataResponse> creditsBalance() {
    return toHttp(pipispyClient.fetchCreditsBalance());
  }

  /**
   * 服务端门禁（§4.3）：免费端点直接放行；3 日窗命中放行（仍取数但不扣）；
   * 其余端点先预估 assert（余额 ≥ estimate 否则 402），调上游成功后再按 U×2 扣用户钱包。
   */
  private MarketingDataResponse handleMarketing(Long userId, String uri, Map<String, Object> params,
                                                Integer expectedCredits) {
    String endpoint = endpointOf(uri);
    String entityId = entityIdOf(params);

    // 1) 永久免费端点（在投商品）
    if (isFreeUri(uri)) {
      MarketingDataResponse res = pipispyClient.postData(uri, params);
      if (res.ok()) res = withBilling(res, 0, creditService.getBalance(userId), true);
      return res;
    }

    // 2) 3 日免费窗：同 id 详情/店铺分析最近 3 天已扣 → 放行不扣（仍取数）
    if (isWindowedUri(uri) && entityId != null
        && recentConsumeExists(userId, endpoint, entityId)) {
      MarketingDataResponse res = pipispyClient.postData(uri, params);
      if (res.ok()) res = withBilling(res, 0, creditService.getBalance(userId), true);
      return res;
    }

    // 2.5) 日调用上限检查（§2.1）
    assertDailyLimit(userId);

    // 3) 预估 assert（防浪费上游额度）
    int estimate = expectedCredits != null ? expectedCredits : defaultEstimate(uri, params, 12);
    Integer balance = creditService.getBalance(userId);
    if (balance == null || balance < estimate) {
      throw new com.tang.common.core.exception.CustomException(
          "Insufficient credits (need ~" + estimate + ", have " + balance + ")", 402, "INSUFFICIENT_CREDITS");
    }

    // 4) 调上游
    MarketingDataResponse res = pipispyClient.postData(uri, params);
    if (!res.ok()) return res;

    // 5) 实扣 = 上游 U × 2
    int upstreamU = res.consumedCredits() != null ? res.consumedCredits() : 0;
    if (upstreamU <= 0) {
      return withBilling(res, 0, creditService.getBalance(userId), false);
    }
    String effectiveCacheKey = entityId != null ? entityId : defaultCacheKey(uri, params);
    com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult charge =
        creditService.chargeMarketingCall(userId, upstreamU, uri, effectiveCacheKey, endpoint, entityId);
    return withBilling(res, charge.chargedCredits(), charge.balanceAfter(), false);
  }

  /** 把计费字段注入响应（用户钱包语义）。 */
  private MarketingDataResponse withBilling(MarketingDataResponse res, int charged,
                                             Integer userBalance, boolean freeWindow) {
    return new MarketingDataResponse(res.ok(), res.source(), res.data(), res.consumedCredits(),
        userBalance, res.code(), res.message(), charged, userBalance, freeWindow);
  }

  /**
   * 参考数据字典（免费静态端点）。
   * 返回地区、类目、店型、CTA 等枚举，供前端筛选器使用。
   * 数据与前端 enums.ts 保持一致；新增维度时前后端同步扩展。
   */
  @GetMapping("/reference/enums")
  public ResponseEntity<Map<String, Object>> referenceEnums() {
    return ResponseEntity.ok(Map.of(
        "region", List.of(
            Map.of("code", "US", "label", "United States"),
            Map.of("code", "GB", "label", "United Kingdom"),
            Map.of("code", "CA", "label", "Canada"),
            Map.of("code", "AU", "label", "Australia"),
            Map.of("code", "DE", "label", "Germany"),
            Map.of("code", "FR", "label", "France"),
            Map.of("code", "IT", "label", "Italy"),
            Map.of("code", "ES", "label", "Spain"),
            Map.of("code", "JP", "label", "Japan"),
            Map.of("code", "KR", "label", "South Korea"),
            Map.of("code", "BR", "label", "Brazil"),
            Map.of("code", "MX", "label", "Mexico")
        ),
        "productCategory", List.of(
            Map.of("code", "beauty", "label", "Beauty & Personal Care"),
            Map.of("code", "pet", "label", "Pet Supplies"),
            Map.of("code", "home", "label", "Home & Kitchen"),
            Map.of("code", "electronics", "label", "Electronics"),
            Map.of("code", "fitness", "label", "Fitness & Sports"),
            Map.of("code", "toys", "label", "Toys & Games"),
            Map.of("code", "apparel", "label", "Apparel & Accessories"),
            Map.of("code", "garden", "label", "Garden & Outdoor"),
            Map.of("code", "auto", "label", "Automotive"),
            Map.of("code", "baby", "label", "Baby & Mother")
        ),
        "ttsCategory", List.of(
            Map.of("code", "beauty", "label", "Beauty"),
            Map.of("code", "women", "label", "Women's Fashion"),
            Map.of("code", "men", "label", "Men's Fashion"),
            Map.of("code", "home", "label", "Home & Living"),
            Map.of("code", "food", "label", "Food & Beverage"),
            Map.of("code", "pet", "label", "Pet Supplies"),
            Map.of("code", "tech", "label", "Tech & Electronics"),
            Map.of("code", "kids", "label", "Kids & Baby")
        ),
        "shopType", List.of(
            Map.of("code", "shopify", "label", "Shopify"),
            Map.of("code", "shoplazza", "label", "Shoplazza"),
            Map.of("code", "shopline", "label", "Shopline"),
            Map.of("code", "shopyy", "label", "Shopyy"),
            Map.of("code", "magento", "label", "Magento"),
            Map.of("code", "woocommerce", "label", "WooCommerce"),
            Map.of("code", "squarespace", "label", "Squarespace"),
            Map.of("code", "wix", "label", "Wix")
        ),
        "cta", List.of(
            Map.of("code", "shop_now", "label", "Shop Now"),
            Map.of("code", "learn_more", "label", "Learn More"),
            Map.of("code", "sign_up", "label", "Sign Up"),
            Map.of("code", "download", "label", "Download"),
            Map.of("code", "get_offer", "label", "Get Offer"),
            Map.of("code", "contact", "label", "Contact Us"),
            Map.of("code", "book", "label", "Book Now")
        )
    ));
  }

  /**
   * Server-side dossier fan-out: batch multiple pipispy calls into a single round-trip.
   * Each item carries its own {@code uri} + {@code params}; results are keyed by {@code tag}.
   * Per-call billing (and pipispy's own 3-day free window) still applies; this only collapses
   * N browser→server hops into 1.
   */
  /**
   * Server-side dossier fan-out with billing gating (§4.3).
   * 每个 item 独立预判 + 调上游 + 按 U×2 扣费；免费/3 日窗 item 不扣。
   * 总预估不足时整体 402（用户可缩小范围重试）。
   */
  @PostMapping("/dossier")
  public ResponseEntity<DossierResponse> dossier(
      HttpServletRequest httpRequest,
      @RequestBody DossierRequest body) {
    Long userId = currentUserId(httpRequest);
    List<DossierRequestItem> items = body.requests() == null ? List.of() : body.requests();
    if (items.size() > MAX_DOSSIER_ITEMS) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new DossierResponse(Map.of(), 0));
    }
    for (DossierRequestItem item : items) {
        assertAllowedUri(item.uri());
    }

    // 日调用上限检查（§2.1）—— dossier 算 1 次
    assertDailyLimit(userId);

    // 总预估（免费/窗口 item 不计入）
    int totalEstimate = 0;
    for (DossierRequestItem item : items) {
        if (!isFreeUri(item.uri())
            && !(isWindowedUri(item.uri()) && entityIdOf(item.params()) != null
                && recentConsumeExists(userId, endpointOf(item.uri()), entityIdOf(item.params())))) {
            totalEstimate += item.expectedCredits() != null ? item.expectedCredits()
                : defaultEstimate(item.uri(), item.params(), 7);
        }
    }
    Integer balance = creditService.getBalance(userId);
    if (balance != null && balance < totalEstimate) {
        throw new com.tang.common.core.exception.CustomException(
            "Insufficient credits (need ~" + totalEstimate + ", have " + balance + ")", 402, "INSUFFICIENT_CREDITS");
    }

    Map<String, MarketingDataResponse> results = new LinkedHashMap<>();
    int totalCharged = 0;
    for (DossierRequestItem item : items) {
        String endpoint = endpointOf(item.uri());
        String entityId = entityIdOf(item.params());
        MarketingDataResponse r;
        if (isFreeUri(item.uri())) {
            r = pipispyClient.postData(item.uri(), item.params());
            if (r.ok()) r = withBilling(r, 0, creditService.getBalance(userId), true);
        } else if (isWindowedUri(item.uri()) && entityId != null
            && recentConsumeExists(userId, endpoint, entityId)) {
            r = pipispyClient.postData(item.uri(), item.params());
            if (r.ok()) r = withBilling(r, 0, creditService.getBalance(userId), true);
        } else {
            r = pipispyClient.postData(item.uri(), item.params());
            if (r.ok()) {
                int upstreamU = r.consumedCredits() != null ? r.consumedCredits() : 0;
                if (upstreamU > 0) {
                    String effectiveCacheKey = entityId != null ? entityId : defaultCacheKey(item.uri(), item.params());
                    com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult ch =
                        creditService.chargeMarketingCall(userId, upstreamU, item.uri(), effectiveCacheKey, endpoint, entityId);
                    r = withBilling(r, ch.chargedCredits(), ch.balanceAfter(), false);
                    totalCharged += ch.chargedCredits();
                } else {
                    r = withBilling(r, 0, creditService.getBalance(userId), false);
                }
            }
        }
        results.put(item.tag() != null ? item.tag() : item.uri(), r);
    }
    return ResponseEntity.ok(new DossierResponse(results, totalCharged));
  }

  // ===== Competitor watchlist =====

  @GetMapping("/competitors")
  public ResponseEntity<List<Map<String, Object>>> listCompetitors(HttpServletRequest httpRequest) {
    Long userId = currentUserId(httpRequest);
    var list = competitorStoreService.listByUser(userId);
    var out = list.stream()
        .map(c -> Map.<String, Object>of("id", c.getStoreId(), "name", c.getStoreName()))
        .toList();
    return ResponseEntity.ok(out);
  }

  public record ToggleCompetitorRequest(String storeId, String storeName) {}

  @PostMapping("/competitors/toggle")
  public ResponseEntity<Map<String, Object>> toggleCompetitor(
      HttpServletRequest httpRequest,
      @RequestBody ToggleCompetitorRequest req) {
    Long userId = currentUserId(httpRequest);
    if (StringUtils.isBlank(req.storeId())) {
      throw new com.tang.common.core.exception.CustomException("storeId is required", 400, "STORE_ID_REQUIRED");
    }
    var result = competitorStoreService.toggle(userId, req.storeId(), req.storeName());
    return ResponseEntity.ok(Map.of(
        "added", result != null,
        "id", req.storeId(),
        "name", req.storeName() != null ? req.storeName() : ""));
  }

  private Long currentUserId(HttpServletRequest httpRequest) {
    Long userId = (Long) httpRequest.getAttribute("userId");
    if (userId == null) {
      throw new com.tang.common.core.exception.CustomException(
              "Unauthorized: login required", 401, "UNAUTHENTICATED");
    }
    return userId;
  }

  // ===== 计费辅助（§4.3） =====

  /** 从 pipispy URI 提取接口名（去前缀），用于流水 endpoint 与 3 日窗查询。 */
  private static String endpointOf(String uri) {
    if (uri == null) return "";
    return uri.startsWith("/v3/api/open/") ? uri.substring("/v3/api/open/".length()) : uri;
  }

  /** 从 params 提取实体 id（详情/店铺分析按 id 享 3 日窗）。 */
  private static String entityIdOf(Map<String, Object> params) {
    if (params == null) return null;
    for (String key : new String[]{"id", "storeId", "productId", "store_id", "product_id"}) {
      Object v = params.get(key);
      if (v != null) return String.valueOf(v);
    }
    return null;
  }

  /** 永久免费端点（在投商品）。 */
  private static boolean isFreeUri(String uri) {
    return uri != null && uri.contains("/store/detail/competition/products");
  }

  /** 享 3 日免费窗的端点（同 id 详情/店铺分析）。 */
  private static boolean isWindowedUri(String uri) {
    if (uri == null) return false;
    return uri.contains("/ad-products/detail")
        || uri.contains("/tiktok-shop/shop/detail")
        || uri.contains("/store/data-analysis")
        || uri.contains("/store/region-analysis")
        || uri.contains("/store/delivery-analysis")
        || uri.contains("/store/ad-trend")
        || uri.contains("/store/longest-run-ads")
        || uri.contains("/store/most-used-ads")
        || uri.contains("/store/fb-pages");
  }

  /** 预估用户积分（= 上游 U × 2 的下界估计）。 */
  private int defaultEstimate(String uri, Map<String, Object> params, int defaultPageSize) {
    if (uri != null && uri.contains("ai-search/image")) return 6;
    // 定稿 §2.2：档案扇出 U≤7 → 收 ≤14。预估取上界避免 assert 通过但实扣 402。
    if (isWindowedUri(uri)) return 14;
    int pageSize = defaultPageSize;
    if (params != null) {
      Object ps = params.get("pageSize");
      if (ps instanceof Number n) pageSize = n.intValue();
      else if (ps instanceof String s) {
        try { pageSize = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
      }
    }
    return Math.max(1, pageSize) * 2;
  }

  /** 稳定请求签名（用于幂等键；不同页/筛选 → 不同键）。 */
  private String defaultCacheKey(String uri, Map<String, Object> params) {
    return uri + "|" + signatureOf(params);
  }

  private String signatureOf(Map<String, Object> params) {
    if (params == null) return "";
    try {
      return objectMapper.writeValueAsString(params);
    } catch (Exception e) {
      return String.valueOf(params);
    }
  }

  /** 同 id 详情/店铺分析最近 3 天是否已扣费（3 日窗判定）。 */
  private boolean recentConsumeExists(Long userId, String endpoint, String entityId) {
    return txnRepository.findRecentConsume(userId, endpoint, entityId,
        Instant.now().minus(3, ChronoUnit.DAYS)) != null;
  }

  /**
   * 日调用上限检查（§2.1）。
   * Starter 80次/日、Growth 200次/日、无订阅 5次/日。
   * 超额抛 429 DAILY_LIMIT_EXCEEDED。
   */
  private void assertDailyLimit(Long userId) {
    int todayCount = dailyUsageRepository.getTodayCount(userId);
    int limit = getDailyLimit(userId);
    if (todayCount >= limit) {
      log.warn("Daily limit exceeded: userId={} count={} limit={}", userId, todayCount, limit);
      throw new com.tang.common.core.exception.CustomException(
          "Daily API call limit reached (" + todayCount + "/" + limit + "). Upgrade your plan for more calls.",
          429, "DAILY_LIMIT_EXCEEDED");
    }
    dailyUsageRepository.incrementToday(userId);
  }

  /** 根据用户订阅等级返回日调用上限。 */
  private int getDailyLimit(Long userId) {
    UserSubscription sub = subscriptionRepository.findActiveByUser(userId);
    if (sub == null) return 5; // 无订阅用户
    return switch (sub.getPlanCode()) {
      case "starter" -> 80;
      case "growth" -> 200;
      default -> 5;
    };
  }

  private void assertAllowedUri(String uri) {
    if (StringUtils.isBlank(uri)) {
        throw new com.tang.common.core.exception.CustomException("uri is required", 400, "URI_REQUIRED");
    }
    String normalized = uri.trim();
    boolean allowed = ALLOWED_URI_PREFIXES.stream().anyMatch(normalized::startsWith);
    if (!allowed) {
        log.warn("Marketing URI not allowed: {}", normalized);
        throw new com.tang.common.core.exception.CustomException("URI not allowed", 403, "URI_NOT_ALLOWED");
    }
  }

  private Map<String, Object> parseParams(String paramsJson) {
    if (StringUtils.isBlank(paramsJson)) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(paramsJson, new TypeReference<>() {});
    } catch (Exception e) {
      log.warn("Invalid marketing params JSON: {}", paramsJson);
      return Map.of();
    }
  }

  private ResponseEntity<MarketingDataResponse> toHttp(MarketingDataResponse res) {
    if (res.ok()) {
      return ResponseEntity.ok(res);
    }
    int status = res.code() != null && res.code() >= 400 && res.code() < 600 ? res.code() : 502;
    if (status == 503 || (res.message() != null && res.message().contains("not configured"))) {
      status = HttpStatus.SERVICE_UNAVAILABLE.value();
    }
    return ResponseEntity.status(status).body(res);
  }
}
