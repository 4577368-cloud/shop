package com.tang.plugin.service.match.image;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.ImageSearchProductVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless client for 1688 image search (Newton Hub {@code find_product}).
 * Uses Spring {@link RestClient} + fastjson2 (consistent with {@code ShopifyGraphqlClient}), no new deps.
 *
 * <p>AK is read from environment variables ({@code ALIBABA_NEWTON_APIKEY}, fallback {@code ALI_1688_AK})
 * and never persisted. Transient failures (409/429/502/503/504, or {@code success=false} with a
 * "繁忙/稍后" message) are retried with exponential backoff. Results are cached in-memory for a short TTL,
 * keyed by {@code imageUrl + limit + query}.
 *
 * <p>Error messages carry a machine-readable prefix so the frontend can differentiate:
 * {@link #ERR_AK_MISSING} (AK 未配置/无效) and {@link #ERR_GATEWAY_BUSY} (网关繁忙/限流/异常).
 */
@Slf4j
@Component
public class Newton1688ImageSearchClient {

    static final String GATEWAY_BASE = "https://skills-gateway.1688.com";
    static final String FIND_PRODUCT_API = "/api/find_product/1.0.0";
    private static final String FULL_URL = GATEWAY_BASE + FIND_PRODUCT_API;
    private static final String BUSINESS_TAG = "4306497";
    private static final int MAX_RETRIES = 4;
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 4;
    private static final long CACHE_TTL_MS = 10L * 60 * 1000;
    private static final Set<Integer> TRANSIENT_STATUS = Set.of(409, 429, 502, 503, 504);

    /** Error message prefix: AK not configured, or gateway rejected auth (401). */
    public static final String ERR_AK_MISSING = "AK_MISSING";
    /** Error message prefix: gateway busy / rate limited / transient / malformed response. */
    public static final String ERR_GATEWAY_BUSY = "GATEWAY_BUSY";

    private final RestClient restClient = RestClient.create();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Image search by public image URL. Returns normalized candidates, ordered by similarity score
     * descending (backend decides top-1; callers must not reorder).
     *
     * @throws CustomException with an {@link #ERR_AK_MISSING} / {@link #ERR_GATEWAY_BUSY} prefixed message.
     */
    public List<ImageSearchProductVO> search(String imageUrl, Integer limit, String query) {
        if (StringUtils.isBlank(imageUrl)) {
            throw new CustomException("1688 image search requires a non-blank imageUrl");
        }
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String normalizedQuery = StringUtils.trimToEmpty(query);
        String cacheKey = imageUrl + "|" + size + "|" + normalizedQuery;

        CacheEntry cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt > now) {
            return cached.data;
        }
        cache.entrySet().removeIf(e -> e.getValue().expireAt <= System.currentTimeMillis());

        String[] ak = Newton1688Signer.extractAkKeys(resolveRawAk());
        if (ak == null) {
            throw new CustomException(ERR_AK_MISSING
                    + ": 1688 AK 未配置，请设置环境变量 ALIBABA_NEWTON_APIKEY 或 ALI_1688_AK");
        }

        JSONObject body = new JSONObject();
        body.put("pageSize", size);
        body.put("purchaseAmount", 1);
        body.put("scoreLevel", "high");
        body.put("tags", BUSINESS_TAG);
        body.put("imageUrl", imageUrl);
        body.put("imgBase64", "");
        if (StringUtils.isNotBlank(normalizedQuery)) {
            body.put("query", normalizedQuery);
        }
        String bodyStr = body.toJSONString();

        RestClientException lastTransport = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Map<String, String> headers = Newton1688Signer.buildAuthHeaders(
                    "POST", FIND_PRODUCT_API, bodyStr, ak[0], ak[1],
                    String.valueOf(System.currentTimeMillis() / 1000L),
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            try {
                String raw = restClient.post()
                        .uri(FULL_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(h -> headers.forEach((k, v) -> {
                            if (!"Content-Type".equalsIgnoreCase(k)) {
                                h.add(k, v);
                            }
                        }))
                        .body(bodyStr)
                        .retrieve()
                        .body(String.class);

                JSONObject result = JSON.parseObject(raw);
                if (result == null) {
                    throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回为空");
                }
                if (Boolean.FALSE.equals(result.getBoolean("success"))) {
                    String msg = StringUtils.firstNonBlank(
                            result.getString("msgInfo"), result.getString("msgCode"), "业务错误");
                    if (isTransientMessage(msg) && attempt < MAX_RETRIES - 1) {
                        backoff(attempt);
                        continue;
                    }
                    throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 图搜失败(" + msg + ")");
                }
                List<ImageSearchProductVO> list = parseProducts(result);
                cache.put(cacheKey, new CacheEntry(list, System.currentTimeMillis() + CACHE_TTL_MS));
                return list;
            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (statusCode == 401) {
                    throw new CustomException(ERR_AK_MISSING + ": 1688 AK 鉴权失败(401)，请检查密钥是否正确", e);
                }
                if (TRANSIENT_STATUS.contains(statusCode) && attempt < MAX_RETRIES - 1) {
                    lastTransport = e;
                    backoff(attempt);
                    continue;
                }
                throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 网关返回错误(" + statusCode + ")", e);
            } catch (RestClientException e) {
                lastTransport = e;
                if (attempt < MAX_RETRIES - 1) {
                    backoff(attempt);
                    continue;
                }
            }
        }
        throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 网关繁忙或限流，请稍后重试", lastTransport);
    }

    private static String resolveRawAk() {
        String primary = System.getenv("ALIBABA_NEWTON_APIKEY");
        if (StringUtils.isNotBlank(primary)) {
            return primary;
        }
        return System.getenv("ALI_1688_AK");
    }

    /** Unwrap the layered gateway envelope down to the object/array carrying the product list. */
    private Object unwrap(JSONObject result) {
        Object model = result.get("model");
        if (model instanceof JSONObject) {
            return model;
        }
        Object data = result.get("data");
        if (data instanceof JSONObject dataObj) {
            Object innerModel = dataObj.get("model");
            return innerModel instanceof JSONObject ? innerModel : dataObj;
        }
        if (data instanceof JSONArray) {
            return data;
        }
        return result;
    }

    private List<ImageSearchProductVO> parseProducts(JSONObject result) {
        Object model = unwrap(result);
        JSONArray dataArr = null;
        if (model instanceof JSONObject modelObj) {
            dataArr = modelObj.getJSONArray("data");
        } else if (model instanceof JSONArray arr) {
            dataArr = arr;
        }
        if (dataArr == null) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回格式异常(data 非列表)");
        }
        List<ImageSearchProductVO> list = new ArrayList<>();
        for (int i = 0; i < dataArr.size(); i++) {
            JSONObject item = dataArr.getJSONObject(i);
            if (item != null) {
                list.add(toVO(item));
            }
        }
        // Backend decides top-1: stable sort by similarity score descending.
        list.sort(Comparator.comparingDouble(
                (ImageSearchProductVO v) -> v.getSimilarityScore() == null ? 0d : v.getSimilarityScore())
                .reversed());
        return list;
    }

    private static ImageSearchProductVO toVO(JSONObject item) {
        String pid = StringUtils.firstNonBlank(
                item.getString("itemId"), item.getString("offerId"), "");
        String detailUrl = item.getString("detailUrl");
        if (StringUtils.isBlank(detailUrl) && StringUtils.isNotBlank(pid)) {
            detailUrl = "https://detail.1688.com/offer/" + pid + ".html";
        }
        String title = StringUtils.firstNonBlank(
                item.getString("title"), item.getString("subject"), item.getString("offerTitle"), "");
        return new ImageSearchProductVO()
                .setProductId(pid)
                .setTitle(StringUtils.trimToEmpty(title))
                .setImageUrl(StringUtils.defaultString(item.getString("imageUrl")))
                .setDetailUrl(StringUtils.defaultString(detailUrl))
                .setPrice(item.getString("currentPrice"))
                .setSupplier(StringUtils.defaultString(item.getString("company")))
                .setSoldCount(item.getLongValue("soldOut"))
                .setSimilarityScore(item.getDoubleValue("score"))
                .setMinOrderQty(item.getLong("quantityBegin"))
                .setInventory(item.getLong("storeAmount"))
                .setSkuId(item.getString("skuId"));
    }

    private static boolean isTransientMessage(String msg) {
        if (StringUtils.isBlank(msg)) {
            return false;
        }
        return StringUtils.containsAny(msg,
                "后端服务调用失败", "系统繁忙", "请稍后", "繁忙", "超时", "限流");
    }

    private static void backoff(int attempt) {
        long millis = (long) (800 * Math.pow(2, attempt));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 图搜重试被中断", e);
        }
    }

    private record CacheEntry(List<ImageSearchProductVO> data, long expireAt) {
    }
}
