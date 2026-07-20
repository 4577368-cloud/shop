package com.tang.plugin.service.catalog;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.service.catalog.TangbuyMallClient.PageInfoResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tangbuy catalog for Path B recommendations / publish. Prefers the live mall API when
 * {@link TangbuyMallClient#isConfigured()} (gateway {@code allSubScriptionSearch} by default);
 * otherwise loads the bundled offline JSON (local/dev fallback).
 */
@Slf4j
@Service
public class TangbuyCatalogService {

    private static final String CATALOG_RESOURCE = "tangbuy-catalog/test-products.json";
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final String DEFAULT_CURRENCY = "CNY";

    @Resource
    private TangbuyMallClient tangbuyMallClient;

    /** Offline fallback only; unused when the mall token is configured. */
    private List<TangbuyCatalogProduct> offlineCatalog = List.of();
    private Map<String, TangbuyCatalogProduct> offlineById = Map.of();

    @PostConstruct
    public void load() {
        loadOfflineJson();
        if (tangbuyMallClient.isConfigured()) {
            log.info("Tangbuy catalog live mall API source={} (offline JSON fallback count={})",
                    tangbuyMallClient.resolvedSource(), offlineCatalog.size());
        } else {
            log.info("Tangbuy catalog using offline JSON count={}", offlineCatalog.size());
        }
    }

    /**
     * First {@code limit} entries in source order. limit defaults to 30, is capped at 100, and any
     * non-positive value falls back to the default.
     */
    public List<TangbuyCatalogProduct> list(Integer limit) {
        return list(0, limit);
    }

    /**
     * A page of {@code limit} entries starting at {@code offset} (source order). offset defaults to 0
     * (negatives clamp to 0); limit defaults to 30 and is capped at 100 per page. Returns an empty list
     * when offset is past the end.
     */
    public List<TangbuyCatalogProduct> list(Integer offset, Integer limit) {
        int from = (offset == null || offset < 0) ? 0 : offset;
        int pageSize = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        if (tangbuyMallClient.isConfigured()) {
            try {
                return listFromMall(from, pageSize);
            } catch (Exception e) {
                log.warn("Tangbuy mall list failed source={}, using offline fallback ({}): {}",
                        tangbuyMallClient.resolvedSource(), offlineCatalog.size(), e.getMessage());
                return listOffline(from, pageSize);
            }
        }

        return listOffline(from, pageSize);
    }

    private List<TangbuyCatalogProduct> listOffline(int from, int pageSize) {
        if (from >= offlineCatalog.size()) {
            return List.of();
        }
        int to = Math.min(from + pageSize, offlineCatalog.size());
        return offlineCatalog.subList(from, to);
    }

    public Optional<TangbuyCatalogProduct> findById(String candidateId) {
        if (StringUtils.isBlank(candidateId)) {
            return Optional.empty();
        }
        String id = candidateId.trim();
        if (tangbuyMallClient.isConfigured()) {
            Optional<TangbuyCatalogProduct> live = findFromMall(id);
            if (live.isPresent()) {
                return live;
            }
            return Optional.ofNullable(offlineById.get(id));
        }
        return Optional.ofNullable(offlineById.get(id));
    }

    public int size() {
        if (tangbuyMallClient.isConfigured()) {
            try {
                PageInfoResult page = tangbuyMallClient.pageInfo(1, 1, null);
                return Math.max(0, page.getTotal());
            } catch (Exception e) {
                log.warn("Tangbuy mall size failed source={}, using offline fallback ({}): {}",
                        tangbuyMallClient.resolvedSource(), offlineCatalog.size(), e.getMessage());
            }
        }
        return offlineCatalog.size();
    }

    /** Diagnostic snapshot for ops (never includes the token). */
    public Map<String, Object> mallStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean live = tangbuyMallClient.isConfigured();
        out.put("mode", live ? "live" : "offline_json");
        out.put("source", tangbuyMallClient.resolvedSource());
        out.put("configured", live);
        if (!live) {
            out.put("offlineCount", offlineCatalog.size());
            out.put("ok", true);
            return out;
        }
        try {
            PageInfoResult page = tangbuyMallClient.pageInfo(1, 1, null);
            out.put("ok", true);
            out.put("total", page.getTotal());
            out.put("sampleRows", page.getRows() == null ? 0 : page.getRows().size());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            out.put("offlineFallbackCount", offlineCatalog.size());
            log.error("Tangbuy mall status probe failed source={}", tangbuyMallClient.resolvedSource(), e);
        }
        return out;
    }

    private List<TangbuyCatalogProduct> listFromMall(int offset, int pageSize) {
        int pageNum = (offset / pageSize) + 1;
        PageInfoResult page = tangbuyMallClient.pageInfo(pageNum, pageSize, null);
        List<TangbuyCatalogProduct> out = new ArrayList<>();
        for (JSONObject row : page.getRows()) {
            TangbuyCatalogProduct product = fromMallRow(row);
            if (product != null) {
                out.add(product);
            }
        }
        // When offset is not page-aligned, drop the leading remainder of the fetched page.
        int skip = offset % pageSize;
        if (skip > 0 && skip < out.size()) {
            return List.copyOf(out.subList(skip, out.size()));
        }
        if (skip > 0) {
            return List.of();
        }
        return List.copyOf(out);
    }

    private Optional<TangbuyCatalogProduct> findFromMall(String candidateId) {
        Object itemId = parseItemId(candidateId);
        if (itemId == null) {
            return Optional.empty();
        }
        try {
            PageInfoResult page = tangbuyMallClient.pageInfo(1, 1, List.of(itemId));
            for (JSONObject row : page.getRows()) {
                TangbuyCatalogProduct product = fromMallRow(row);
                if (product != null && candidateId.equals(product.getCandidateId())) {
                    return Optional.of(product);
                }
                // Accept when API returns the same id even if status filter dropped mapping.
                if (product != null) {
                    return Optional.of(product);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Tangbuy mall findById failed candidateId={}", candidateId, e);
            return Optional.empty();
        }
    }

    /**
     * Map one pageInfo row. Returns null when status is present and not {@code ON}, or when itemId
     * is missing.
     */
    static TangbuyCatalogProduct fromMallRow(JSONObject row) {
        if (row == null) {
            return null;
        }
        String status = StringUtils.trimToNull(row.getString("status"));
        if (status != null && !"ON".equalsIgnoreCase(status)) {
            return null;
        }
        Object rawId = row.get("itemId");
        if (rawId == null) {
            return null;
        }
        String candidateId = String.valueOf(rawId).trim();
        if (candidateId.isEmpty() || "null".equals(candidateId)) {
            return null;
        }

        String imageUrl = null;
        JSONArray images = row.getJSONArray("imageList");
        if (images == null || images.isEmpty()) {
            images = row.getJSONArray("itemImages");
        }
        if (images != null && !images.isEmpty()) {
            imageUrl = StringUtils.trimToNull(images.getString(0));
        }

        BigDecimal price = row.getBigDecimal("price");
        if (price == null) {
            price = row.getBigDecimal("providerPrice");
        }

        return new TangbuyCatalogProduct()
                .setCandidateId(candidateId)
                .setTangbuyProductId(candidateId)
                .setTitle(row.getString("itemName"))
                .setPrice(price)
                .setCurrency(DEFAULT_CURRENCY)
                .setImageUrl(imageUrl)
                .setTangbuyUrl(StringUtils.trimToNull(row.getString("detailUrl")))
                .setUrl1688(null)
                .setOfferId1688(null)
                .setSkuId(null)
                .setFrontSkuId(null)
                .setSkuAttr(null)
                .setSupplierShop(StringUtils.trimToNull(row.getString("providerShopName")))
                .setUpstreamPlatform(StringUtils.trimToNull(row.getString("dataSource")))
                .setBarcode(null);
    }

    /** Prefer numeric Long for itemIdList; fall back to the raw string if not parseable. */
    private static Object parseItemId(String candidateId) {
        try {
            return Long.parseLong(candidateId);
        } catch (NumberFormatException e) {
            return candidateId;
        }
    }

    private void loadOfflineJson() {
        ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
        if (!resource.exists()) {
            log.warn("Tangbuy catalog resource not found path={}", CATALOG_RESOURCE);
            return;
        }
        String raw;
        try (InputStream in = resource.getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Tangbuy catalog read failed path={}", CATALOG_RESOURCE, e);
            return;
        }

        JSONObject root = JSON.parseObject(raw);
        JSONArray products = root == null ? null : root.getJSONArray("products");
        if (products == null || products.isEmpty()) {
            log.warn("Tangbuy catalog empty path={}", CATALOG_RESOURCE);
            return;
        }

        List<TangbuyCatalogProduct> loaded = new ArrayList<>(products.size());
        Map<String, TangbuyCatalogProduct> index = new LinkedHashMap<>();
        int discarded = 0;
        for (int i = 0; i < products.size(); i++) {
            JSONObject node = products.getJSONObject(i);
            if (node == null) {
                discarded++;
                continue;
            }
            String candidateId = resolveCandidateId(node);
            if (StringUtils.isBlank(candidateId)) {
                discarded++;
                log.warn("Tangbuy catalog discard record (no stable id) index={} title={}",
                        i, node.getString("title"));
                continue;
            }
            loaded.add(toProduct(node, candidateId));
            index.putIfAbsent(candidateId, loaded.get(loaded.size() - 1));
        }

        this.offlineCatalog = Collections.unmodifiableList(loaded);
        this.offlineById = Collections.unmodifiableMap(index);
        log.info("Tangbuy catalog loaded offline count={} discarded={} path={}",
                loaded.size(), discarded, CATALOG_RESOURCE);
    }

    /**
     * Stable id: tangbuy_product_id, else offer_id_1688[_sku_id], else offer_id_1688; null when none.
     */
    private static String resolveCandidateId(JSONObject node) {
        String tangbuyProductId = StringUtils.trimToNull(node.getString("tangbuy_product_id"));
        if (tangbuyProductId != null) {
            return tangbuyProductId;
        }
        String offerId = StringUtils.trimToNull(node.getString("offer_id_1688"));
        if (offerId == null) {
            return null;
        }
        String skuId = StringUtils.trimToNull(node.getString("sku_id"));
        return skuId == null ? offerId : offerId + "_" + skuId;
    }

    private static TangbuyCatalogProduct toProduct(JSONObject node, String candidateId) {
        String currency = StringUtils.trimToNull(node.getString("currency"));
        return new TangbuyCatalogProduct()
                .setCandidateId(candidateId)
                .setTangbuyProductId(StringUtils.trimToNull(node.getString("tangbuy_product_id")))
                .setTitle(node.getString("title"))
                .setPrice(node.getBigDecimal("price"))
                .setCurrency(currency == null ? DEFAULT_CURRENCY : currency)
                .setImageUrl(node.getString("image_url"))
                .setTangbuyUrl(node.getString("tangbuy_url"))
                .setUrl1688(node.getString("url_1688"))
                .setOfferId1688(node.getString("offer_id_1688"))
                .setSkuId(node.getString("sku_id"))
                .setFrontSkuId(node.getString("front_sku_id"))
                .setSkuAttr(node.getString("sku_attr"))
                .setSupplierShop(node.getString("supplier_shop"))
                .setUpstreamPlatform(node.getString("upstream_platform"))
                .setBarcode(node.getString("barcode"));
    }
}
