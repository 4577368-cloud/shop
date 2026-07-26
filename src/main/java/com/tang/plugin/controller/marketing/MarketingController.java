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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  @PostMapping("/data")
  public ResponseEntity<MarketingDataResponse> postData(@RequestBody MarketingDataRequest body) {
    assertAllowedUri(body.uri());
    return toHttp(pipispyClient.postData(body.uri(), body.params()));
  }

  /**
   * GET variant for dev tunnels that strip POST bodies. {@code params} is URL-encoded JSON object.
   */
  @GetMapping("/data")
  public ResponseEntity<MarketingDataResponse> getData(
      @RequestParam String uri,
      @RequestParam(required = false) String params) {
    assertAllowedUri(uri);
    Map<String, Object> map = parseParams(params);
    return toHttp(pipispyClient.postData(uri, map));
  }

  @GetMapping("/credits-balance")
  public ResponseEntity<MarketingDataResponse> creditsBalance() {
    return toHttp(pipispyClient.fetchCreditsBalance());
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
  @PostMapping("/dossier")
  public ResponseEntity<DossierResponse> dossier(@RequestBody DossierRequest body) {
    List<DossierRequestItem> items = body.requests() == null ? List.of() : body.requests();
    if (items.size() > MAX_DOSSIER_ITEMS) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new DossierResponse(Map.of(), 0));
    }
    for (DossierRequestItem item : items) {
        assertAllowedUri(item.uri());
    }
    Map<String, MarketingDataResponse> results = pipispyClient.fanOut(items);
    int total = results.values().stream()
        .map(MarketingDataResponse::consumedCredits)
        .filter(Objects::nonNull)
        .mapToInt(Integer::intValue)
        .sum();
    return ResponseEntity.ok(new DossierResponse(results, total));
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
