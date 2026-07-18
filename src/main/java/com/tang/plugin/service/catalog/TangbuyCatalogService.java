package com.tang.plugin.service.catalog;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the Tangbuy offline catalog (bundled classpath JSON) into memory at startup and serves
 * read-only lookups. Order is preserved from the source file. Test-phase data source; swapping to a
 * real Tangbuy catalog later only touches this class. No DB, no external calls (M1-1).
 */
@Slf4j
@Service
public class TangbuyCatalogService {

    private static final String CATALOG_RESOURCE = "tangbuy-catalog/test-products.json";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final String DEFAULT_CURRENCY = "CNY";

    /** Insertion-ordered, immutable after load. */
    private List<TangbuyCatalogProduct> catalog = List.of();
    private Map<String, TangbuyCatalogProduct> byId = Map.of();

    @PostConstruct
    public void load() {
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

        this.catalog = Collections.unmodifiableList(loaded);
        this.byId = Collections.unmodifiableMap(index);
        log.info("Tangbuy catalog loaded count={} discarded={} path={}", loaded.size(), discarded, CATALOG_RESOURCE);
    }

    /**
     * First {@code limit} entries in source order. limit defaults to 50, is capped at 100, and any
     * non-positive value falls back to the default.
     */
    public List<TangbuyCatalogProduct> list(Integer limit) {
        int effective = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return catalog.size() <= effective ? catalog : catalog.subList(0, effective);
    }

    public Optional<TangbuyCatalogProduct> findById(String candidateId) {
        if (StringUtils.isBlank(candidateId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(candidateId));
    }

    public int size() {
        return catalog.size();
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
