package com.tang.plugin.controller.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.dto.billing.BillingDtos.MarketingChargeResult;
import com.tang.plugin.dto.marketing.MarketingDtos.MarketingDataResponse;
import com.tang.plugin.domain.entity.user.UserSubscription;
import com.tang.plugin.repository.UserDailyUsageRepository;
import com.tang.plugin.repository.UserSubscriptionRepository;
import com.tang.plugin.service.billing.CreditService;
import com.tang.plugin.service.marketing.PipispyClient;
import com.tang.plugin.service.marketing.UpstreamPoolGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 以图搜编排端点（单次富内容、单次计费）。
 *
 * <p>流程：submit(image-url) → 轮询 status(10~20s) → resultSummary → product/search。
 * 整条链路按 product/search 真实 consumed_credits(+编排步) 合计后 ×2 计一次费，
 * 且只占用 1 次日限额（而非 4 次 /data 调用），符合"每次付费调用返回富 dossier"铁律。
 *
 * <p>支持两种图片来源：
 * <ul>
 *   <li>imageUrl：直接走 submit/image-url（纯 JSON，生产/本地均可）。</li>
 *   <li>file：上传后托管到公开临时端点 /public/aisearch/{id}（免 JWT），pipispy 抓取后删除。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/marketing")
@RequiredArgsConstructor
public class AiImageSearchController {

    private static final String SUBMIT_URI = "/v3/api/open/ai-search/image/submit/image-url";
    private static final String STATUS_URI = "/v3/api/open/ai-search/image/status";
    private static final String SUMMARY_URI = "/v3/api/open/ai-search/image/resultSummary";
    private static final String PRODUCT_URI = "/v3/api/open/ai-search/image/product/search";
    private static final Path TMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "aisearch-tmp");

    private final PipispyClient pipispyClient;
    private final CreditService creditService;
    private final UpstreamPoolGuard upstreamPoolGuard;
    private final UserDailyUsageRepository dailyUsageRepository;
    private final UserSubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/ai-search-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MarketingDataResponse> search(
            HttpServletRequest request,
            @RequestParam(value = "imageUrl", required = false) String imageUrl,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "4") int pageSize,
            @RequestParam(value = "expectedCredits", required = false) Integer expectedCredits) {

        String resolvedUrl = null;
        Path tmpFile = null;
        try {
            // 1) 解析图片来源
            if (file != null && !file.isEmpty()) {
                resolvedUrl = hostUploadedFile(request, file);
                tmpFile = TMP_DIR.resolve(filenameOf(resolvedUrl));
            } else if (imageUrl != null && !imageUrl.isBlank()) {
                resolvedUrl = imageUrl.trim();
            } else {
                return bad(400, "imageUrl or file is required");
            }

            Long userId = currentUserId(request);
            int ps = Math.min(Math.max(pageSize, 1), 50);
            // 真实成本 = page_size(检索按条计费) + 编排步(submit/status/resultSummary 各约 1)；
            // 计费用实际 consumed 合计，这里只作门禁下界估计。
            int serverEstimate = (ps + 3) * 2;
            int estimate = expectedCredits != null ? Math.max(expectedCredits, serverEstimate) : serverEstimate;

            // 2) 日限额 + L0 熔断 + 余额门禁（B4：服务端估计作下界）
            assertDailyLimit(userId);
            upstreamPoolGuard.assertAvailableForPaidCall();
            Integer balance = creditService.getBalance(userId);
            if (balance == null || balance < estimate) {
                throw new CustomException(
                        "Insufficient credits (need ~" + estimate + ", have " + balance + ")",
                        402, "INSUFFICIENT_CREDITS");
            }

            // 3) submit
            MarketingDataResponse submit = pipispyClient.postData(SUBMIT_URI, Map.of("image_url", resolvedUrl));
            if (!submit.ok()) {
                return ResponseEntity.status(statusOf(submit)).body(withBilling(submit, 0, creditService.getBalance(userId), false));
            }
            String imageId = extractImageId(submit.data());
            if (imageId == null) {
                return bad(502, "pipispy submit did not return image_id");
            }

            // 4) 轮询处理状态（10~20s）
            pollUntilDone(imageId);

            // 5) 结果摘要（best-effort）
            MarketingDataResponse summary = pipispyClient.postData(SUMMARY_URI, Map.of("image_id", imageId));

            // 6) 产品检索
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("image_id", imageId);
            params.put("current_page", page);
            params.put("page_size", ps);
            MarketingDataResponse product = pipispyClient.postData(PRODUCT_URI, params);
            if (!product.ok()) {
                return ResponseEntity.status(statusOf(product)).body(withBilling(product, 0, creditService.getBalance(userId), false));
            }

            // 7) 合计上游消耗并一次性计费（U × 2）
            int upstreamU = sumConsumed(submit, summary, product);
            int charged = 0;
            int balanceAfter = creditService.getBalance(userId);
            if (upstreamU > 0) {
                MarketingChargeResult charge = creditService.chargeMarketingCall(
                        userId, upstreamU, PRODUCT_URI, imageId, "ai-search-image", imageId);
                charged = charge.chargedCredits();
                balanceAfter = charge.balanceAfter();
            }

            // 8) 映射 list + page
            JsonNode data = product.data();
            JsonNode listNode = (data != null && data.has("data")) ? data.get("data") : data;
            JsonNode pageNode = (data != null && data.has("page")) ? data.get("page") : null;
            List<Map<String, Object>> list = new ArrayList<>();
            if (listNode != null && listNode.isArray()) {
                for (JsonNode item : listNode) {
                    list.add(mapProduct(item));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("list", list);
            out.put("page", pageNode != null ? pageNode : objectMapper.createObjectNode());
            MarketingDataResponse res = new MarketingDataResponse(
                    true, "pipispy", objectMapper.valueToTree(out),
                    product.consumedCredits(), product.remainingCredits(),
                    200, null, charged, balanceAfter, false);
            return ResponseEntity.ok(res);

        } catch (CustomException ce) {
            int http = (ce.getHttpStatus() >= 400 && ce.getHttpStatus() < 600) ? ce.getHttpStatus() : 402;
            return ResponseEntity.status(http)
                    .body(new MarketingDataResponse(false, "pipispy", null, null, null, ce.getHttpStatus(), ce.getMessage(), null, null, null));
        } catch (Exception e) {
            log.error("ai-search-image failed", e);
            return ResponseEntity.status(502)
                    .body(new MarketingDataResponse(false, "pipispy", null, null, null, 502,
                            "ai-search-image failed: " + e.getMessage(), null, null, null));
        } finally {
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
        }
    }

    // ===== 图片托管（仅文件上传路径需要）=====

    private String hostUploadedFile(HttpServletRequest request, MultipartFile file) throws Exception {
        Files.createDirectories(TMP_DIR);
        String ext = extOf(file);
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Path p = TMP_DIR.resolve(name);
        file.transferTo(p.toFile());
        // 公开 URL（无 JWT）：由 pipispy 服务端抓取；处理完在 finally 删除。
        return publicBaseUrl(request) + "/public/aisearch/" + name;
    }

    private String publicBaseUrl(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");
        if (proto == null) proto = request.getScheme();
        if (host == null) {
            host = request.getServerName()
                    + ((request.getServerPort() != 80 && request.getServerPort() != 443) ? ":" + request.getServerPort() : "");
        }
        return proto + "://" + host;
    }

    // ===== 轮询 =====

    private void pollUntilDone(String imageId) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            Thread.sleep(2500);
            MarketingDataResponse st = pipispyClient.postData(STATUS_URI, Map.of("image_id", imageId));
            if (!st.ok() || st.data() == null) continue;
            JsonNode d = st.data();
            if (isDone(textAt(d, "status"))) return;
            JsonNode inner = d.get("data");
            if (inner != null) {
                if (isDone(textAt(inner, "status"))) return;
                JsonNode prog = inner.get("progress");
                if (prog != null && prog.isNumber() && prog.intValue() >= 100) return;
            }
        }
        log.warn("ai-search-image status poll timed out after ~25s; proceeding anyway imageId={}", imageId);
    }

    private static boolean isDone(String status) {
        return status != null && (status.equals("done") || status.equals("completed")
                || status.equals("success") || status.equals("ready"));
    }

    // ===== 字段提取 / 映射 =====

    private String extractImageId(JsonNode data) {
        if (data == null) return null;
        String v = textAt(data, "image_id");
        if (v != null) return v;
        JsonNode d = data.get("data");
        if (d != null) {
            v = textAt(d, "image_id");
            if (v != null) return v;
        }
        return null;
    }

    private int sumConsumed(MarketingDataResponse... responses) {
        int total = 0;
        for (MarketingDataResponse r : responses) {
            if (r != null && r.consumedCredits() != null) total += r.consumedCredits();
        }
        return total;
    }

    private Map<String, Object> mapProduct(JsonNode it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", textAt(it, "id"));
        m.put("image", textAt(it, "image"));
        m.put("title", textAt(it, "title"));
        m.put("platform", textAt(it, "platform"));
        m.put("usdPrice", numAt(it, "usd_price"));
        m.put("price", numAt(it, "price"));
        m.put("currency", textAt(it, "currency"));
        m.put("similarity", 0); // 真实响应不回传每商品相似度分（默认 sort=9 仅排序）
        m.put("store", textAt(it, "platform")); // 产品检索无店铺名，弱化"看竞店"为平台维度
        m.put("playCount", numAt(it, "play_count"));
        m.put("diggCount", numAt(it, "digg_count"));
        m.put("commentCount", numAt(it, "comment_count"));
        m.put("shareCount", numAt(it, "share_count"));
        m.put("videoCount", numAt(it, "video_count"));
        m.put("putDays", numAt(it, "put_days"));
        m.put("popularPersonCount", numAt(it, "popular_person_count"));
        JsonNode adp = it.get("ad_platform_list");
        if (adp != null && adp.isArray()) {
            List<String> arr = new ArrayList<>();
            adp.forEach(n -> arr.add(n.asText()));
            m.put("adPlatformList", arr);
        }
        return m;
    }

    // ===== 门禁/计费辅助（与 MarketingController 同源逻辑）=====

    private Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new CustomException("Unauthorized: login required", 401, "UNAUTHENTICATED");
        }
        return userId;
    }

    private void assertDailyLimit(Long userId) {
        int today = dailyUsageRepository.getTodayCount(userId);
        int limit = getDailyLimit(userId);
        if (today >= limit) {
            throw new CustomException(
                    "Daily API call limit reached (" + today + "/" + limit + "). Upgrade your plan for more calls.",
                    429, "DAILY_LIMIT_EXCEEDED");
        }
        dailyUsageRepository.incrementToday(userId);
    }

    private int getDailyLimit(Long userId) {
        UserSubscription sub = subscriptionRepository.findActiveByUser(userId);
        if (sub == null) return 5;
        return switch (sub.getPlanCode()) {
            case "sub_starter" -> 80;
            case "sub_growth" -> 200;
            default -> 5;
        };
    }

    // ===== 小工具 =====

    private static String textAt(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode n = node.get(key);
        return (n != null && !n.isNull()) ? n.asText() : null;
    }

    private static Number numAt(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode n = node.get(key);
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.numberValue();
        try {
            return Double.parseDouble(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String e = name.substring(name.lastIndexOf('.')).toLowerCase();
            if (e.matches("\\.[a-z0-9]{2,5}")) return e;
        }
        String ct = file.getContentType();
        if (ct != null) {
            if (ct.contains("png")) return ".png";
            if (ct.contains("jpg") || ct.contains("jpeg")) return ".jpg";
            if (ct.contains("webp")) return ".webp";
            if (ct.contains("gif")) return ".gif";
        }
        return ".jpg";
    }

    private static String filenameOf(String url) {
        int idx = url.lastIndexOf('/');
        return idx >= 0 ? url.substring(idx + 1) : url;
    }

    private static int statusOf(MarketingDataResponse res) {
        Integer c = res.code();
        if (c != null && c >= 400 && c < 600) return c;
        return 502;
    }

    private static MarketingDataResponse withBilling(MarketingDataResponse res, int charged, int balance, boolean free) {
        return new MarketingDataResponse(res.ok(), res.source(), res.data(), res.consumedCredits(),
                res.remainingCredits(), res.code(), res.message(), charged, balance, free);
    }

    private static ResponseEntity<MarketingDataResponse> bad(int code, String msg) {
        return ResponseEntity.status(code)
                .body(new MarketingDataResponse(false, "pipispy", null, null, null, code, msg, null, null, null));
    }
}
