package com.tang.plugin.service.bundle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.BundlesFeatureVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleStatusMapVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleVO;
import com.tang.plugin.domain.entity.bundle.ShopProductBundle;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.query.bundle.ShopBundleCreateReq;
import com.tang.plugin.enums.bundle.ShopBundleStatus;
import com.tang.plugin.repository.bundle.ShopProductBundleRepository;
import com.tang.plugin.service.bundle.component.ShopifyProductBundleComponent;
import com.tang.plugin.service.user.ShopifyStoreAuthService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class ShopBundleService {

    private static final int POLL_ATTEMPTS = 20;
    private static final long POLL_SLEEP_MS = 750L;

    @Resource
    private ShopProductBundleRepository bundleRepository;
    @Resource
    private ShopifyProductBundleComponent bundleComponent;
    @Resource
    private ShopifyStoreAuthService shopifyStoreAuthService;

    public BundlesFeatureVO feature(String shopName) {
        ShopifyStoreAuth auth = requireAuth(shopName);
        return bundleComponent.fetchBundlesFeature(
                shopName, auth.getShopDomain(), auth.getAccessToken());
    }

    public ShopBundleStatusMapVO statusMap(String shopName) {
        BundlesFeatureVO feature = feature(shopName);
        List<ShopProductBundle> rows = bundleRepository.listActiveByShop(shopName);
        Map<String, ShopBundleStatusMapVO.CardStatus> byProduct = new HashMap<>();
        for (ShopProductBundle row : rows) {
            List<ShopBundleVO.ComponentVO> components = parseComponents(row.getComponentsJson());
            putCard(byProduct, row.getContextProductId(), row, components, false, true);
            if (StringUtils.isNotBlank(row.getParentProductId())) {
                putCard(byProduct, numericId(row.getParentProductId()), row, components, true, false);
            }
            for (ShopBundleVO.ComponentVO c : components) {
                putCard(byProduct, c.getProductId(), row, components, false, true);
            }
        }
        return new ShopBundleStatusMapVO().setFeature(feature).setByProductId(byProduct);
    }

    public ShopBundleVO getById(String shopName, Long id) {
        ShopProductBundle row = bundleRepository.findById(id)
                .orElseThrow(() -> new CustomException("Bundle not found"));
        if (!shopName.equals(row.getShopName())) {
            throw new CustomException("Bundle shop mismatch");
        }
        return toVo(row);
    }

    /**
     * products/delete — parent gone → DISSOLVED; component/context gone → STALE.
     * Only rows managed by this app. Errors are logged by callers; this method does not throw.
     */
    public void onShopifyProductDeleted(String shopName, String productGidOrId) {
        String productId = numericId(productGidOrId);
        if (StringUtils.isAnyBlank(shopName, productId)) return;
        List<ShopProductBundle> rows = bundleRepository.listByShopTouchingProduct(shopName, productId);
        for (ShopProductBundle row : rows) {
            if (row.getManagedByApp() != 1) continue;
            ShopBundleStatus status = row.getStatus();
            if (status == null
                    || status == ShopBundleStatus.DISSOLVED
                    || (status != ShopBundleStatus.CREATING
                    && status != ShopBundleStatus.ACTIVE
                    && status != ShopBundleStatus.FAILED
                    && status != ShopBundleStatus.STALE)) {
                continue;
            }
            String parentId = numericId(row.getParentProductId());
            if (productId.equals(parentId)) {
                bundleRepository.updateStatus(
                        row.getId(),
                        ShopBundleStatus.DISSOLVED,
                        "Parent product deleted on Shopify");
                log.info("Bundle dissolved shop={} id={} parentDeleted={}",
                        shopName, row.getId(), productId);
                continue;
            }
            boolean asComponent = componentIds(row).contains(productId);
            boolean asContext = productId.equals(numericId(row.getContextProductId()));
            if (asComponent || asContext) {
                String msg = asComponent
                        ? "Component product deleted on Shopify; re-sync required"
                        : "Context product deleted on Shopify; re-sync required";
                bundleRepository.updateStatus(row.getId(), ShopBundleStatus.STALE, msg);
                log.info("Bundle marked STALE shop={} id={} deletedProduct={}",
                        shopName, row.getId(), productId);
            }
        }
    }

    /**
     * products/update — ACTIVE managed bundles touching parent/component → STALE
     * (Admin-side edits require merchant to re-check in App). Does not throw.
     * Skips rows synced within the last few minutes to avoid echo from our own create/price writes.
     */
    public void onShopifyProductUpserted(String shopName, String productGidOrId) {
        String productId = numericId(productGidOrId);
        if (StringUtils.isAnyBlank(shopName, productId)) return;
        Instant graceCutoff = Instant.now().minusSeconds(180);
        List<ShopProductBundle> rows = bundleRepository.listByShopTouchingProduct(shopName, productId);
        for (ShopProductBundle row : rows) {
            if (row.getManagedByApp() != 1) continue;
            if (row.getStatus() != ShopBundleStatus.ACTIVE) continue;
            if (row.getSyncedAt() != null && row.getSyncedAt().isAfter(graceCutoff)) {
                continue;
            }
            String parentId = numericId(row.getParentProductId());
            boolean asParent = productId.equals(parentId);
            boolean asComponent = componentIds(row).contains(productId);
            if (!asParent && !asComponent) continue;
            bundleRepository.updateStatus(
                    row.getId(),
                    ShopBundleStatus.STALE,
                    "Parent or component changed on Shopify; re-sync required");
            log.info("Bundle marked STALE after product update shop={} id={} product={}",
                    shopName, row.getId(), productId);
        }
    }

    public ShopBundleVO createAndWait(ShopBundleCreateReq req) {
        if (req == null || StringUtils.isAnyBlank(req.getShopName(), req.getContextProductId())) {
            throw new CustomException("shopName and contextProductId required");
        }
        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        BundlesFeatureVO feature = bundleComponent.fetchBundlesFeature(
                req.getShopName(), auth.getShopDomain(), auth.getAccessToken());
        if (!feature.isEligibleForBundles()) {
            throw new CustomException(StringUtils.defaultIfBlank(
                    feature.getIneligibilityReason(),
                    "Shop is not eligible for Shopify Bundles (check checkout / sales channel)"));
        }

        List<ShopifyProductBundleComponent.ComponentSpec> specs = normalizeComponents(req);
        String title = StringUtils.trimToEmpty(req.getTitle());
        if (StringUtils.isBlank(title)) {
            title = "Bundle " + req.getContextProductId();
        }

        JSONArray snapshot = new JSONArray();
        for (ShopifyProductBundleComponent.ComponentSpec spec : specs) {
            JSONObject c = new JSONObject();
            c.put("productId", numericId(spec.productId()));
            c.put("quantity", spec.quantity());
            try {
                JSONObject p = bundleComponent.fetchProductOptions(
                        req.getShopName(), auth.getShopDomain(), auth.getAccessToken(),
                        ShopifyProductBundleComponent.toProductGid(spec.productId()));
                if (p != null) c.put("title", p.getString("title"));
            } catch (Exception ignored) {
                /* title optional in snapshot */
            }
            snapshot.add(c);
        }

        ShopProductBundle row = new ShopProductBundle()
                .setShopName(req.getShopName())
                .setContextProductId(numericId(req.getContextProductId()))
                .setParentTitle(title)
                .setParentPrice(req.getParentPrice())
                .setComponentsJson(snapshot.toJSONString())
                .setStatus(ShopBundleStatus.CREATING)
                .setManagedByApp(1);
        long id = bundleRepository.insert(row);
        row.setId(id);

        try {
            String operationId = bundleComponent.createBundle(
                    req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), title, specs);
            row.setShopifyOperationId(operationId);
            JSONObject op = pollUntilDone(req.getShopName(), auth, operationId);
            applyOperationResult(row, op);
            if (row.getStatus() == ShopBundleStatus.ACTIVE
                    && req.getParentPrice() != null
                    && StringUtils.isNotBlank(row.getParentProductId())
                    && StringUtils.isNotBlank(row.getParentVariantId())) {
                try {
                    bundleComponent.updateParentVariantPrice(
                            req.getShopName(),
                            auth.getShopDomain(),
                            auth.getAccessToken(),
                            row.getParentProductId(),
                            row.getParentVariantId(),
                            req.getParentPrice());
                    row.setParentPrice(req.getParentPrice());
                } catch (Exception priceErr) {
                    log.warn("Bundle parent price update skipped shop={} id={}: {}",
                            req.getShopName(), id, priceErr.getMessage());
                }
            }
            bundleRepository.updateAfterPoll(row);
            return toVo(row);
        } catch (Exception e) {
            log.error("Bundle create failed shop={} id={}", req.getShopName(), id, e);
            bundleRepository.markFailed(id, e.getMessage());
            throw e instanceof CustomException ce ? ce : new CustomException(e.getMessage());
        }
    }

    private JSONObject pollUntilDone(String shopName, ShopifyStoreAuth auth, String operationId)
            throws InterruptedException {
        JSONObject last = null;
        for (int i = 0; i < POLL_ATTEMPTS; i++) {
            last = bundleComponent.pollOperation(
                    shopName, auth.getShopDomain(), auth.getAccessToken(), operationId);
            if (last == null) {
                Thread.sleep(POLL_SLEEP_MS);
                continue;
            }
            String status = last.getString("status");
            if ("COMPLETE".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                return last;
            }
            Thread.sleep(POLL_SLEEP_MS);
        }
        throw new CustomException("Bundle operation timed out: " + operationId);
    }

    private void applyOperationResult(ShopProductBundle row, JSONObject op) {
        if (op == null) {
            row.setStatus(ShopBundleStatus.FAILED);
            row.setErrorMessage("Empty productOperation");
            return;
        }
        String status = op.getString("status");
        JSONArray errors = op.getJSONArray("userErrors");
        if ("FAILED".equalsIgnoreCase(status) || (errors != null && !errors.isEmpty())) {
            row.setStatus(ShopBundleStatus.FAILED);
            row.setErrorMessage(errors == null || errors.isEmpty()
                    ? "Bundle operation failed"
                    : errors.getJSONObject(0).getString("message"));
            return;
        }
        JSONObject product = op.getJSONObject("product");
        if (product == null || StringUtils.isBlank(product.getString("id"))) {
            row.setStatus(ShopBundleStatus.FAILED);
            row.setErrorMessage("Bundle operation completed without product");
            return;
        }
        row.setParentProductId(numericId(product.getString("id")));
        row.setParentTitle(StringUtils.defaultIfBlank(product.getString("title"), row.getParentTitle()));
        JSONObject variants = product.getJSONObject("variants");
        JSONArray nodes = variants == null ? null : variants.getJSONArray("nodes");
        if (nodes != null && !nodes.isEmpty()) {
            JSONObject v0 = nodes.getJSONObject(0);
            row.setParentVariantId(numericId(v0.getString("id")));
            if (row.getParentPrice() == null && StringUtils.isNotBlank(v0.getString("price"))) {
                try {
                    row.setParentPrice(new BigDecimal(v0.getString("price")));
                } catch (Exception ignored) {
                    /* keep null */
                }
            }
        }
        row.setStatus(ShopBundleStatus.ACTIVE);
        row.setErrorMessage(null);
        row.setSyncedAt(Instant.now());
    }

    private List<ShopifyProductBundleComponent.ComponentSpec> normalizeComponents(ShopBundleCreateReq req) {
        Map<String, Integer> qtyById = new LinkedHashMap<>();
        String contextId = numericId(req.getContextProductId());
        qtyById.put(contextId, 1);
        if (req.getComponents() != null) {
            for (ShopBundleCreateReq.ComponentInput c : req.getComponents()) {
                if (c == null || StringUtils.isBlank(c.getProductId())) continue;
                String id = numericId(c.getProductId());
                int q = c.getQuantity() == null ? 1 : Math.max(1, c.getQuantity());
                qtyById.merge(id, q, Integer::sum);
            }
        }
        if (qtyById.size() < 2) {
            throw new CustomException("Select at least one additional product as a bundle component");
        }
        List<ShopifyProductBundleComponent.ComponentSpec> specs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : qtyById.entrySet()) {
            specs.add(new ShopifyProductBundleComponent.ComponentSpec(e.getKey(), e.getValue()));
        }
        return specs;
    }

    private ShopifyStoreAuth requireAuth(String shopName) {
        return shopifyStoreAuthService.findActiveFreshByShopName(shopName)
                .orElseThrow(() -> new CustomException("Shopify store not authorized: " + shopName));
    }

    private static void putCard(
            Map<String, ShopBundleStatusMapVO.CardStatus> map,
            String productId,
            ShopProductBundle row,
            List<ShopBundleVO.ComponentVO> components,
            boolean asParent,
            boolean asComponent) {
        if (StringUtils.isBlank(productId)) return;
        ShopBundleStatusMapVO.CardStatus existing = map.get(productId);
        if (existing != null && "ACTIVE".equals(existing.getStatus()) && !"ACTIVE".equals(row.getStatus().name())) {
            return;
        }
        ShopBundleStatusMapVO.CardStatus card = existing == null
                ? new ShopBundleStatusMapVO.CardStatus()
                : existing;
        card.setBundleId(row.getId());
        card.setStatus(row.getStatus().name());
        card.setParentProductId(numericId(row.getParentProductId()));
        card.setParentTitle(row.getParentTitle());
        card.setComponentCount(components.size());
        card.setAsParent(card.isAsParent() || asParent);
        card.setAsComponent(card.isAsComponent() || asComponent);
        card.setManagedByApp(row.getManagedByApp() == 1);
        map.put(productId, card);
    }

    private static ShopBundleVO toVo(ShopProductBundle row) {
        return new ShopBundleVO()
                .setId(row.getId())
                .setShopName(row.getShopName())
                .setContextProductId(row.getContextProductId())
                .setParentProductId(numericId(row.getParentProductId()))
                .setParentVariantId(numericId(row.getParentVariantId()))
                .setParentTitle(row.getParentTitle())
                .setParentPrice(row.getParentPrice())
                .setStatus(row.getStatus().name())
                .setManagedByApp(row.getManagedByApp() == 1)
                .setErrorMessage(row.getErrorMessage())
                .setSyncedAt(row.getSyncedAt())
                .setComponents(parseComponents(row.getComponentsJson()));
    }

    private static List<ShopBundleVO.ComponentVO> parseComponents(String json) {
        List<ShopBundleVO.ComponentVO> list = new ArrayList<>();
        if (StringUtils.isBlank(json)) return list;
        try {
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                list.add(new ShopBundleVO.ComponentVO()
                        .setProductId(numericId(o.getString("productId")))
                        .setQuantity(o.getIntValue("quantity", 1))
                        .setTitle(o.getString("title")));
            }
        } catch (Exception e) {
            /* ignore malformed */
        }
        return list;
    }

    private static Set<String> componentIds(ShopProductBundle row) {
        Set<String> ids = new HashSet<>();
        for (ShopBundleVO.ComponentVO c : parseComponents(row.getComponentsJson())) {
            if (StringUtils.isNotBlank(c.getProductId())) ids.add(c.getProductId());
        }
        return ids;
    }

    private static String numericId(String gidOrId) {
        return ShopifyProductBundleComponent.numericProductId(gidOrId);
    }
}
