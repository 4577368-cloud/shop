package com.tang.plugin.controller.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataRequest;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierRequest;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierRequestItem;
import com.tang.plugin.dto.marketing.MarketingDtos.DossierResponse;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import com.tang.plugin.service.marketing.PipispyClient;
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

    private final PipispyClient pipispyClient;
    private final ObjectMapper objectMapper;

  @PostMapping("/data")
  public ResponseEntity<MarketingDataResponse> postData(@RequestBody MarketingDataRequest body) {
    return toHttp(pipispyClient.postData(body.uri(), body.params()));
  }

  /**
   * GET variant for dev tunnels that strip POST bodies. {@code params} is URL-encoded JSON object.
   */
  @GetMapping("/data")
  public ResponseEntity<MarketingDataResponse> getData(
      @RequestParam String uri,
      @RequestParam(required = false) String params) {
    Map<String, Object> map = parseParams(params);
    return toHttp(pipispyClient.postData(uri, map));
  }

  @GetMapping("/credits-balance")
  public ResponseEntity<MarketingDataResponse> creditsBalance() {
    return toHttp(pipispyClient.fetchCreditsBalance());
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
    Map<String, MarketingDataResponse> results = pipispyClient.fanOut(items);
    int total = results.values().stream()
        .map(MarketingDataResponse::consumedCredits)
        .filter(Objects::nonNull)
        .mapToInt(Integer::intValue)
        .sum();
    return ResponseEntity.ok(new DossierResponse(results, total));
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
