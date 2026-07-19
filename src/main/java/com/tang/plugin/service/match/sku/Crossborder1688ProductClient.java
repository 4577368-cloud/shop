package com.tang.plugin.service.match.sku;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.sku.OfferDetailVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuAttributeVO;
import com.tang.plugin.domain.dto.match.sku.OfferSkuVO;
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
 * Stateless client for the 1688 cross-border distribution offer detail API
 * ({@code com.alibaba.fenxiao.crossborder:product.search.queryProductDetail}, aka 多语言商详),
 * used by S1-b to obtain an offer's full SKU matrix (spec attributes + skuId + price + stock).
 *
 * <p>Uses the AOP {@code param2} protocol on {@code gw.open.1688.com}: app credentials + user
 * {@code access_token} + {@code _aop_signature} (see {@link Alibaba1688AopSigner}). This is a separate
 * auth path from the Newton image-search AK and never mixes with it. Credentials come from environment
 * variables and are never persisted. Results are cached in-memory for a short TTL, keyed by
 * {@code offerId + country}.
 *
 * <p>Error messages carry a machine-readable prefix: {@link #ERR_CRED_MISSING} (凭证未配置),
 * {@link #ERR_TOKEN_INVALID} (令牌无效/过期), {@link #ERR_OFFER_NOT_FOUND} (offer 不存在),
 * {@link #ERR_GATEWAY_BUSY} (网关繁忙/异常).
 */
@Slf4j
@Component
public class Crossborder1688ProductClient {

    static final String GATEWAY_BASE = "https://gw.open.1688.com/openapi/";
    static final String NAMESPACE = "com.alibaba.fenxiao.crossborder";
    static final String API_NAME = "product.search.queryProductDetail";
    private static final long CACHE_TTL_MS = 10L * 60 * 1000;

    public static final String ERR_CRED_MISSING = "AOP_CRED_MISSING";
    public static final String ERR_TOKEN_INVALID = "AOP_TOKEN_INVALID";
    public static final String ERR_OFFER_NOT_FOUND = "OFFER_NOT_FOUND";
    public static final String ERR_GATEWAY_BUSY = "GATEWAY_BUSY";

    private final RestClient restClient = RestClient.create();
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Fetch and normalize the SKU matrix of a 1688 offer.
     *
     * @param offerId 1688 offer id (numeric string)
     * @param country translation language for {@code *Trans} fields (e.g. {@code en})
     * @throws CustomException prefixed with one of the {@code ERR_*} codes.
     */
    public OfferDetailVO queryProductDetail(String offerId, String country) {
        if (StringUtils.isBlank(offerId)) {
            throw new CustomException("queryProductDetail requires a non-blank offerId");
        }
        String lang = StringUtils.defaultIfBlank(country, "en");
        String cacheKey = offerId + "|" + lang;
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
        JSONObject param = new JSONObject();
        param.put("offerId", Long.parseLong(offerId.trim()));
        param.put("country", lang);
        // Signed params (excluding _aop_signature), ascending key order handled by the signer.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);
        params.put("offerDetailParam", param.toJSONString());
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

        OfferDetailVO vo = parse(raw, offerId);
        cache.put(cacheKey, new CacheEntry(vo, System.currentTimeMillis() + CACHE_TTL_MS));
        return vo;
    }

    private OfferDetailVO parse(String raw, String offerId) {
        JSONObject root = raw == null ? null : JSON.parseObject(raw);
        if (root == null) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回为空");
        }
        // AOP-level error envelope (auth/quota failures surface here rather than as result).
        String errorCode = root.getString("error_code");
        if (StringUtils.isNotBlank(errorCode)) {
            String msg = StringUtils.firstNonBlank(root.getString("error_message"), errorCode);
            if (isAuthError(errorCode + " " + msg)) {
                throw new CustomException(ERR_TOKEN_INVALID + ": 1688 授权失败(" + msg + ")");
            }
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 开放平台错误(" + msg + ")");
        }
        JSONObject result = root.getJSONObject("result");
        if (result == null) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 返回格式异常(缺少 result)");
        }
        if (!Boolean.TRUE.equals(result.getBoolean("success"))) {
            String msg = StringUtils.firstNonBlank(result.getString("message"), result.getString("code"), "业务错误");
            if (isAuthError(msg)) {
                throw new CustomException(ERR_TOKEN_INVALID + ": 1688 授权失败(" + msg + ")");
            }
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 商详查询失败(" + msg + ")");
        }
        JSONObject inner = result.getJSONObject("result");
        if (inner == null) {
            throw new CustomException(ERR_OFFER_NOT_FOUND + ": 未获取到 offer(" + offerId + ") 详情");
        }

        OfferDetailVO vo = new OfferDetailVO()
                .setOfferId(StringUtils.defaultIfBlank(inner.getString("offerId"), offerId))
                .setSubject(inner.getString("subject"))
                .setSubjectTrans(inner.getString("subjectTrans"))
                .setMinOrderQuantity(inner.getInteger("minOrderQuantity"));
        JSONObject image = inner.getJSONObject("productImage");
        if (image != null) {
            vo.setWhiteImageUrl(image.getString("whiteImage"));
        }
        vo.setSkus(parseSkus(inner.getJSONArray("productSkuInfos")));
        return vo;
    }

    private static List<OfferSkuVO> parseSkus(JSONArray arr) {
        List<OfferSkuVO> skus = new ArrayList<>();
        if (arr == null) {
            return skus;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject s = arr.getJSONObject(i);
            if (s == null) {
                continue;
            }
            OfferSkuVO sku = new OfferSkuVO()
                    .setSkuId(s.getString("skuId"))
                    .setSpecId(s.getString("specId"))
                    .setCargoNumber(s.getString("cargoNumber"))
                    .setPrice(s.getString("price"))
                    .setConsignPrice(s.getString("consignPrice"))
                    .setPromotionPrice(s.getString("promotionPrice"))
                    .setAmountOnSale(s.getInteger("amountOnSale"));
            JSONObject fenxiao = s.getJSONObject("fenxiaoPriceInfo");
            if (fenxiao != null) {
                sku.setFenxiaoOnePiecePrice(fenxiao.getString("onePiecePrice"));
                sku.setFenxiaoOfferPrice(fenxiao.getString("offerPrice"));
            }
            sku.setSkuAttributes(parseAttributes(s.getJSONArray("skuAttributes")));
            skus.add(sku);
        }
        return skus;
    }

    private static List<OfferSkuAttributeVO> parseAttributes(JSONArray arr) {
        List<OfferSkuAttributeVO> attrs = new ArrayList<>();
        if (arr == null) {
            return attrs;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject a = arr.getJSONObject(i);
            if (a == null) {
                continue;
            }
            attrs.add(new OfferSkuAttributeVO()
                    .setAttributeId(a.getString("attributeId"))
                    .setAttributeName(a.getString("attributeName"))
                    .setValue(a.getString("value"))
                    .setAttributeNameTrans(a.getString("attributeNameTrans"))
                    .setValueTrans(a.getString("valueTrans"))
                    .setSkuImageUrl(a.getString("skuImageUrl")));
        }
        return attrs;
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

    private record CacheEntry(OfferDetailVO data, long expireAt) {
    }
}
