package com.tang.plugin.service.match.image;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.match.image.ImageUploadResultVO;
import com.tang.plugin.service.match.sku.Alibaba1688AopSigner;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless client for the official 1688 image upload
 * ({@code com.alibaba.fenxiao.crossborder:product.image.upload}, 上传图片获取imageId). Downloads an image by
 * url, base64-encodes it, and uploads via the AOP {@code param2} protocol to obtain an {@code imageId} that
 * {@link Alibaba1688ImageSearchClient} can search with — the enabler for image-searching a Shopify-CDN image
 * that is not directly reachable by 1688.
 *
 * <p>Same AOP auth path / env credentials as the other cross-border clients; never mixes with the Newton AK.
 * The exact application-level field name is not fully documented, so {@link #parse} reads the {@code imageId}
 * defensively from the common envelope shapes. Errors carry the shared {@code AOP_*} / {@code GATEWAY_BUSY}
 * prefixes.
 */
@Slf4j
@Component
public class Alibaba1688ImageUploadClient {

    static final String NAMESPACE = "com.alibaba.fenxiao.crossborder";
    static final String API_NAME = "product.image.upload";
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    public static final String ERR_CRED_MISSING = "AOP_CRED_MISSING";
    public static final String ERR_TOKEN_INVALID = "AOP_TOKEN_INVALID";
    public static final String ERR_GATEWAY_BUSY = "GATEWAY_BUSY";
    public static final String ERR_IMAGE_UNREADABLE = "IMAGE_UNREADABLE";

    private final RestClient restClient = RestClient.create();

    /**
     * Upload the image at {@code imageUrl} and return its {@code imageId}.
     *
     * @throws CustomException prefixed with one of the {@code ERR_*} codes.
     */
    public ImageUploadResultVO uploadByUrl(String imageUrl) {
        if (StringUtils.isBlank(imageUrl)) {
            throw new CustomException("image upload requires a non-blank imageUrl");
        }
        String base64 = toBase64(fetchImageBytes(imageUrl));

        String appKey = env("ALIBABA_1688_APP_KEY");
        String appSecret = env("ALIBABA_1688_APP_SECRET");
        String accessToken = env("ALIBABA_1688_ACCESS_TOKEN");
        if (StringUtils.isAnyBlank(appKey, appSecret, accessToken)) {
            throw new CustomException(ERR_CRED_MISSING
                    + ": 1688 开放平台凭证未配置，请设置 ALIBABA_1688_APP_KEY / ALIBABA_1688_APP_SECRET / ALIBABA_1688_ACCESS_TOKEN");
        }

        String apiPath = "param2/1/" + NAMESPACE + "/" + API_NAME + "/" + appKey;
        JSONObject param = new JSONObject();
        param.put("imageBase64", base64);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);
        params.put("uploadImageParam", param.toJSONString());
        String signature = Alibaba1688AopSigner.sign(apiPath, params, appSecret);

        Map<String, String> form = new LinkedHashMap<>(params);
        form.put("_aop_signature", signature);

        String raw;
        try {
            raw = restClient.post()
                    .uri(Alibaba1688ImageSearchClient.GATEWAY_BASE + apiPath)
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
        return parse(raw);
    }

    /** Read {@code imageId} defensively from the (loosely documented) upload envelope. */
    ImageUploadResultVO parse(String raw) {
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
        if (result.containsKey("success") && !Boolean.TRUE.equals(result.getBoolean("success"))) {
            String msg = StringUtils.firstNonBlank(result.getString("message"), result.getString("code"), "业务错误");
            throw classify(msg);
        }
        String imageId = extractImageId(result);
        if (StringUtils.isBlank(imageId)) {
            throw new CustomException(ERR_GATEWAY_BUSY + ": 1688 上传未返回 imageId(" + StringUtils.left(raw, 256) + ")");
        }
        return new ImageUploadResultVO().setImageId(imageId);
    }

    /** {@code imageId} may live under result.result (object or bare string) or directly on result. */
    private static String extractImageId(JSONObject result) {
        Object inner = result.get("result");
        if (inner instanceof JSONObject obj) {
            String id = obj.getString("imageId");
            if (StringUtils.isNotBlank(id)) {
                return id;
            }
        } else if (inner instanceof String s && StringUtils.isNotBlank(s)) {
            return s;
        }
        return result.getString("imageId");
    }

    private byte[] fetchImageBytes(String imageUrl) {
        try {
            byte[] bytes = restClient.get().uri(imageUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new CustomException(ERR_IMAGE_UNREADABLE + ": 图片下载为空(" + imageUrl + ")");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new CustomException(ERR_IMAGE_UNREADABLE + ": 图片过大(" + bytes.length + " bytes)");
            }
            return bytes;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ERR_IMAGE_UNREADABLE + ": 无法下载图片(" + imageUrl + ")", e);
        }
    }

    private static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static CustomException classify(String msg) {
        if (isAuthError(msg)) {
            return new CustomException(ERR_TOKEN_INVALID + ": 1688 授权失败(" + msg + ")");
        }
        return new CustomException(ERR_GATEWAY_BUSY + ": 1688 图片上传失败(" + msg + ")");
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
}
