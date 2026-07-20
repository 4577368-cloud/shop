package com.tang.plugin.service.catalog;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TangbuyMallProperties;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for Tangbuy Admin mall ES {@code pageInfo}. Token must be configured via
 * {@link TangbuyMallProperties#getToken()}; this class never logs the token.
 */
@Slf4j
@Component
public class TangbuyMallClient {

    private static final String PAGE_INFO_PATH = "/prod-api/product-mall/admin/es/product/pageInfo";

    @Resource
    private TangbuyMallProperties properties;

    private volatile RestClient restClient;

    public boolean isConfigured() {
        return StringUtils.isNotBlank(properties.resolvedToken());
    }

    /**
     * Paginated mall listing. {@code itemIdList} may be null for a normal page, or a singleton list
     * to resolve one product by id.
     */
    public PageInfoResult pageInfo(int pageNum, int pageSize, List<Object> itemIdList) {
        if (!isConfigured()) {
            throw new CustomException("Tangbuy mall token not configured (TANG_PLUGIN_TANGBUY_MALL_TOKEN)");
        }
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 1;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageNum", pageNum);
        body.put("pageSize", pageSize);
        body.put("pageType", "0");
        body.put("categoryIdList", List.of());
        body.put("manageLabelIdList", List.of());
        body.put("labelIdList", List.of());
        if (itemIdList != null && !itemIdList.isEmpty()) {
            body.put("itemIdList", itemIdList);
        }

        String url = StringUtils.removeEnd(StringUtils.trimToEmpty(properties.getBaseUrl()), "/")
                + PAGE_INFO_PATH;
        String raw;
        try {
            raw = client().post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.resolvedToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .body(JSON.toJSONString(body))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.error("Tangbuy mall pageInfo HTTP failed pageNum={} pageSize={} url={}",
                    pageNum, pageSize, url, e);
            throw new CustomException("Tangbuy mall pageInfo HTTP failed: " + e.getMessage(), e);
        }

        JSONObject root = JSON.parseObject(raw);
        if (root == null) {
            throw new CustomException("Tangbuy mall pageInfo empty response");
        }
        Integer code = root.getInteger("code");
        Boolean success = root.getBoolean("success");
        if (code != null && code != 200) {
            throw new CustomException("Tangbuy mall pageInfo code=" + code + " msg=" + root.getString("msg"));
        }
        if (Boolean.FALSE.equals(success)) {
            throw new CustomException("Tangbuy mall pageInfo success=false msg=" + root.getString("msg"));
        }

        int total = root.getIntValue("total");
        JSONArray rows = root.getJSONArray("rows");
        List<JSONObject> list = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (row != null) {
                    list.add(row);
                }
            }
        }
        return new PageInfoResult()
                .setTotal(total)
                .setRows(Collections.unmodifiableList(list));
    }

    private RestClient client() {
        RestClient existing = restClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (restClient == null) {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(Math.max(1_000, properties.getConnectTimeoutMs()));
                factory.setReadTimeout(Math.max(1_000, properties.getReadTimeoutMs()));
                restClient = RestClient.builder().requestFactory(factory).build();
            }
            return restClient;
        }
    }

    @Data
    @Accessors(chain = true)
    public static class PageInfoResult {
        private int total;
        private List<JSONObject> rows = List.of();
    }
}
