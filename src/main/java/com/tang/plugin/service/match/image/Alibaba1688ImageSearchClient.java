package com.tang.plugin.service.match.image;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchItemVO;
import com.tang.plugin.domain.dto.match.image.OfferImageSearchResultVO;
import com.tang.plugin.service.match.sku.Alibaba1688AopSigner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless client for the official 1688 cross-border image search
 * ({@code com.alibaba.fenxiao.crossborder:product.search.imageQuery}, 多语言图搜). Same AOP {@code param2}
 * auth path on {@code gw.open.1688.com} as {@link com.tang.plugin.service.match.sku.Crossborder1688ProductClient}
 * (app credentials from env + user {@code access_token} + {@code _aop_signature}); never mixes with the
 * Newton skills-gateway AK scheme.
 *
 * <p>Accepts either a publicly reachable {@code imageAddress} or an {@code imageId} obtained from
 * {@link Alibaba1688ImageUploadClient}, plus an optional {@code keyword}/{@code auxiliaryText} correction
 * term (the platform-native equivalent of the A3-2a title/LLM query layering). Returns normalized offers
 * carrying multilingual titles, prices, MOQ and a directly-openable detail url. Results are cached in-memory
 * for a short TTL. An empty result is a normal miss, not an error.
 *
 * <p>Error messages carry the shared machine-readable prefixes {@link #ERR_CRED_MISSING} /
 * {@link #ERR_TOKEN_INVALID} / {@link #ERR_GATEWAY_BUSY}.
 */
@Slf4j
@Component
public class Alibaba1688ImageSearchClient {

    static final String GATEWAY_BASE = "https://gw.open.1688.com/openapi/";
    static final String NAMESPACE = "com.alibaba.fenxiao.crossborder";
    static final String API_NAME = "product.search.imageQuery";
    private static final long CACHE_TTL_MS = 5L * 60 * 1000;
    private static final int MAX_PAGE_SIZE = 50;

    public static final String ERR_CRED_MISSING = "AOP_CRED_MISSING";
    public static final String ERR_TOKEN_INVALID = "AOP_TOKEN_INVALID";
    public static final String ERR_GATEWAY_BUSY = "GATEWAY_BUSY";

    private final RestClient restClient = RestClient.create();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Search 1688 offers by image (+ optional correction query).
     *
     * @param imageAddress publicly reachable image url (mutually complementary with {@code imageId})
     * @param imageId      image token from {@code product.image.upload} (preferred when the source image is
     *                     not alicdn-reachable, e.g. a Shopify-CDN image)
     * @param keyword      optional correction keyword (title / LLM term); blank to omit
     * @param auxiliaryText optional auxiliary text (e.g. "热卖的"); blank to omit
     * @param country      translation language for {@code *Trans} fields (e.g. {@code en})
     * @param beginPage    1-based page number (defaults to 1 when null/&lt;1)
     * @param pageSize     page size (defaults to 5, capped at {@value #MAX_PAGE_SIZE})
     * @throws CustomException prefixed with one of the {@code ERR_*} codes when the call fails.
     */
    public OfferImageSearchResultVO searchByImage(String imageAddress, String imageId, String keyword,
                                                  String auxiliaryText, String country,
                                                  Integer beginPage, Integer pageSize) {
        if (StringUtils.isAllBlank(imageAddress, imageId)) {
            throw new CustomException("imageQuery requires imageAddress or imageId");
        }
        String lang = StringUtils.defaultIfBlank(country, "en");
        int page = beginPage == null || beginPage < 1 ? 1 : beginPage;
        int size = pageSize == null || pageSize < 1 ? 5 : Math.min(pageSize, MAX_PAGE_SIZE);

        String cacheKey = String.join("|", StringUtils.defaultString(imageId),
                StringUtils.defaultString(imageAddress), StringUtils.defaultString(keyword),
                StringUtils.defaultString(auxiliaryText), lang, String.valueOf(page), String.valueOf(size));
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expireAt > now) {
            return cached.data;
        }
        cache.entrySet().removeIf(e -> e.getValue().expireAt <= System.currentTimeMillis());

        String appKey = env("ALIBABA_1688_APP_KEY");
        String appSecret = env("ALIBABA_1688_APP_SECRET");
        String accessToken = env("ALIBABA_1688_ACCESS_TOKEN");
        if (StringUtils.isAnyBlank(appKey, appSecret, accessToken)) {
            throw new CustomException(ERR_CRED_MISSING
                    + ": 1688 开放平台凭证未配置，请设置 ALIBABA_1688_APP_KEY / ALIBABA_1688_APP_SECRET / ALIBABA_1688_ACCESS_TOKEN");
        }

        String apiPath = "param2/1/" + NAMESPACE + "/" + API_NAME + "/" + appKey;
        JSONObject query = new JSONObject();
        if (StringUtils.isNotBlank(imageId)) {
            query.put("imageId", imageId);
        }
        if (StringUtils.isNotBlank(imageAddress)) {
            query.put("imageAddress", imageAddress);
        }
        if (StringUtils.isNotBlank(keyword)) {
            query.put("keyword", keyword);
        }
        if (StringUtils.isNotBlank(auxiliaryText)) {
            query.put("auxiliaryText", auxiliaryText);
        }
        query.put("country", lang);
        query.put("beginPage", page);
        query.put("pageSize", size);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);
        params.put("offerQueryParam", query.toJSONString());
        String signature = Alibaba1688AopSigner.sign(apiPath, params, appSecret);

        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("_aop_signature", signature);

        String raw;
        try {
            raw = restClient.post()
                    .uri(GATEWAY_BASE + apiPath)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(encodeForm(form))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new CustomException(ERR_TOKEN_INVALID + ": 1688 授权失败(" + status + ")，请检查 access_token", e);
            }
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 开放平台返回错误(" + status + ")", e);
        } catch (Exception e) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 开放平台不可达", e);
        }

        OfferImageSearchResultVO vo = parse(raw, imageId);
        cache.put(cacheKey, new CacheEntry(vo, System.currentTimeMillis() + CACHE_TTL_MS));
        return vo;
    }

    /** Parse and normalize the imageQuery envelope. Package-private for unit testing with a doc sample. */
    OfferImageSearchResultVO parse(String raw, String imageId) {
        JSONObject root = raw == null ? null : JSON.parseObject(raw);
        if (root == null) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回为空");
        }
        String errorCode = root.getString("error_code");
        if (StringUtils.isNotBlank(errorCode)) {
            String msg = StringUtils.firstNonBlank(root.getString("error_message"), errorCode);
            throw classify(msg);
        }
        JSONObject result = root.getJSONObject("result");
        if (result == null) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回格式异常(缺少 result)");
        }
        if (!Boolean.TRUE.equals(result.getBoolean("success"))) {
            String msg = StringUtils.firstNonBlank(result.getString("message"), result.getString("code"), "业务错误");
            throw classify(msg);
        }
        OfferImageSearchResultVO vo = new OfferImageSearchResultVO().setImageId(imageId);
        JSONObject inner = result.getJSONObject("result");
        if (inner == null) {
            // Success envelope but no payload: treat as an empty result rather than an error.
            return vo.setItems(new ArrayList<>());
        }
        vo.setTotalRecords(inner.getInteger("totalRecords"))
                .setTotalPage(inner.getInteger("totalPage"))
                .setPageSize(inner.getInteger("pageSize"))
                .setCurrentPage(inner.getInteger("currentPage"))
                .setItems(parseItems(inner.getJSONArray("data")));
        return vo;
    }

    private static List<OfferImageSearchItemVO> parseItems(JSONArray arr) {
        List<OfferImageSearchItemVO> items = new ArrayList<>();
        if (arr == null) {
            return items;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o == null) {
                continue;
            }
            OfferImageSearchItemVO item = new OfferImageSearchItemVO()
                    .setOfferId(o.getString("offerId"))
                    .setSubject(o.getString("subject"))
                    .setSubjectTrans(o.getString("subjectTrans"))
                    .setImageUrl(o.getString("imageUrl"))
                    .setMinOrderQuantity(o.getInteger("minOrderQuantity"))
                    .setMonthSold(o.getInteger("monthSold"))
                    .setRepurchaseRate(o.getString("repurchaseRate"))
                    .setCompanyName(o.getString("companyName"))
                    .setDetailUrl(o.getString("promotionURL"));
            JSONObject price = o.getJSONObject("priceInfo");
            if (price != null) {
                item.setPrice(price.getString("price"))
                        .setConsignPrice(price.getString("consignPrice"))
                        .setPromotionPrice(price.getString("promotionPrice"));
            }
            items.add(item);
        }
        return items;
    }

    private static CustomException classify(String msg) {
        if (isAuthError(msg)) {
            return new CustomException(ERR_TOKEN_INVALID + ": 1688 授权失败(" + msg + ")");
        }
        return new CustomException(ERR_GATEWAY_BUSY + ": 1688 图搜失败(" + msg + ")");
    }

    private static boolean isAuthError(String msg) {
        if (StringUtils.isBlank(msg)) {
            return false;
        }
        String m = msg.toLowerCase();
        return StringUtils.containsAny(m, "token", "auth", "授权", "令牌", "unauthorized", "expired");
    }

    private static String env(String name) {
        return StringUtils.trimToEmpty(System.getenv(name));
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(StringUtils.defaultString(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private record CacheEntry(OfferImageSearchResultVO data, long expireAt) {
    }
}
