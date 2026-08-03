package com.tang.plugin.service.bundle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.bundle.BundlesFeatureVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleStatusMapVO;
import com.tang.plugin.domain.dto.bundle.ShopBundleVO;
import com.tang.plugin.domain.dto.bundle.ShopComboSaveVO;
import com.tang.plugin.domain.dto.bundle.ShopGiftSaveVO;
import com.tang.plugin.domain.entity.bundle.ShopProductBundle;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.domain.query.bundle.ShopBundleCreateReq;
import com.tang.plugin.domain.query.bundle.ShopBundleUpdateReq;
import com.tang.plugin.domain.query.bundle.ShopComboSaveReq;
import com.tang.plugin.domain.query.bundle.ShopGiftSaveReq;
import com.tang.plugin.enums.bundle.ShopBundleStatus;
import com.tang.plugin.repository.ShopProductBindingRepository;
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
    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;

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

        List<ShopifyProductBundleComponent.ComponentSpec> specs = normalizeComponents(
                req.getContextProductId(), req.getContextVariantId(), req.getComponents());
        assertAllComponentsBound(req.getShopName(), specs);

        String title = StringUtils.trimToEmpty(req.getTitle());
        if (StringUtils.isBlank(title)) {
            title = "Bundle " + req.getContextProductId();
        }

        JSONArray snapshot = snapshotComponents(req.getShopName(), auth, specs);

        ShopProductBundle row = new ShopProductBundle()
                .setShopName(req.getShopName())
                .setContextProductId(numericId(req.getContextProductId()))
                .setParentTitle(title)
                .setParentPrice(req.getParentPrice())
                .setDiscountPercent(req.getDiscountPercent())
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
            afterActiveWrite(req.getShopName(), auth, row, req.getParentPrice(), req.getDiscountPercent());
            bundleRepository.updateAfterPoll(row);
            return toVo(row);
        } catch (Exception e) {
            log.error("Bundle create failed shop={} id={}", req.getShopName(), id, e);
            bundleRepository.markFailed(id, e.getMessage());
            throw e instanceof CustomException ce ? ce : new CustomException(e.getMessage());
        }
    }

    public ShopBundleVO updateAndWait(ShopBundleUpdateReq req) {
        if (req == null || req.getBundleId() == null || StringUtils.isBlank(req.getShopName())) {
            throw new CustomException("shopName and bundleId required");
        }
        ShopProductBundle row = bundleRepository.findById(req.getBundleId())
                .orElseThrow(() -> new CustomException("Bundle not found"));
        if (!req.getShopName().equals(row.getShopName())) {
            throw new CustomException("Bundle shop mismatch");
        }
        if (row.getManagedByApp() != 1) {
            throw new CustomException("Bundle is managed by another app");
        }
        if (StringUtils.isBlank(row.getParentProductId())) {
            throw new CustomException("Bundle has no Shopify parent yet — create first");
        }
        if (row.getStatus() == ShopBundleStatus.FAILED) {
            throw new CustomException(
                    "Failed bundle cannot be updated — create a new bundle instead");
        }
        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        List<ShopifyProductBundleComponent.ComponentSpec> specs = normalizeComponents(
                row.getContextProductId(), req.getContextVariantId(), req.getComponents());
        assertAllComponentsBound(req.getShopName(), specs);

        String title = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(req.getTitle()), row.getParentTitle());
        JSONArray snapshot = snapshotComponents(req.getShopName(), auth, specs);
        row.setParentTitle(title);
        row.setParentPrice(req.getParentPrice() != null ? req.getParentPrice() : row.getParentPrice());
        row.setDiscountPercent(req.getDiscountPercent() != null ? req.getDiscountPercent() : row.getDiscountPercent());
        row.setComponentsJson(snapshot.toJSONString());
        row.setStatus(ShopBundleStatus.CREATING);
        bundleRepository.updateAfterPoll(row);

        try {
            String keepParent = row.getParentProductId();
            String keepVariant = row.getParentVariantId();
            String operationId = bundleComponent.updateBundle(
                    req.getShopName(), auth.getShopDomain(), auth.getAccessToken(),
                    keepParent, title, specs);
            row.setShopifyOperationId(operationId);
            JSONObject op = pollUntilDone(req.getShopName(), auth, operationId);
            applyOperationResult(row, op);
            if (StringUtils.isBlank(row.getParentProductId())) {
                row.setParentProductId(keepParent);
            }
            if (StringUtils.isBlank(row.getParentVariantId())) {
                row.setParentVariantId(keepVariant);
            }
            if (row.getStatus() == ShopBundleStatus.FAILED
                    && op != null
                    && "COMPLETE".equalsIgnoreCase(op.getString("status"))) {
                row.setStatus(ShopBundleStatus.ACTIVE);
                row.setErrorMessage(null);
                row.setSyncedAt(Instant.now());
            }
            afterActiveWrite(req.getShopName(), auth, row, row.getParentPrice(), row.getDiscountPercent());
            bundleRepository.updateAfterPoll(row);
            return toVo(row);
        } catch (Exception e) {
            log.error("Bundle update failed shop={} id={}", req.getShopName(), row.getId(), e);
            bundleRepository.markFailed(row.getId(), e.getMessage());
            throw e instanceof CustomException ce ? ce : new CustomException(e.getMessage());
        }
    }

    /**
     * Track B — persist same-product combo on the original Shopify product.
     * Does not create a Fixed Bundle parent. Checkout apply waits for Discount Function.
     */
    public ShopComboSaveVO saveSameProductCombo(ShopComboSaveReq req) {
        if (req == null || StringUtils.isAnyBlank(req.getShopName(), req.getProductId(), req.getKind())) {
            throw new CustomException("shopName, productId and kind required");
        }
        String kind = req.getKind().trim().toLowerCase();
        if (!"qty_discount".equals(kind) && !"variant_pair".equals(kind)) {
            throw new CustomException("kind must be qty_discount or variant_pair");
        }
        if (!shopProductBindingRepository.hasActiveItemBinding(
                req.getShopName(), numericId(req.getProductId()))) {
            throw new CustomException("Product must have an ACTIVE source binding before saving combo");
        }

        JSONObject config = new JSONObject();
        config.put("kind", kind);
        config.put("label", StringUtils.defaultIfBlank(req.getLabel(), ""));
        if ("qty_discount".equals(kind)) {
            int qty = req.getQty() == null ? 2 : Math.max(2, req.getQty());
            BigDecimal pct = req.getDiscountPercent() == null
                    ? BigDecimal.ZERO
                    : req.getDiscountPercent().max(BigDecimal.ZERO).min(new BigDecimal("100"));
            config.put("qty", qty);
            config.put("discountPercent", pct);
        } else {
            if (req.getVariantIds() == null || req.getVariantIds().size() < 2) {
                throw new CustomException("variant_pair requires at least two variantIds");
            }
            JSONArray vids = new JSONArray();
            for (String id : req.getVariantIds()) {
                if (StringUtils.isNotBlank(id)) vids.add(numericId(id));
            }
            if (vids.size() < 2) {
                throw new CustomException("variant_pair requires at least two variantIds");
            }
            config.put("variantIds", vids);
            if (req.getDiscountPercent() != null) {
                config.put("discountPercent",
                        req.getDiscountPercent().max(BigDecimal.ZERO).min(new BigDecimal("100")));
            }
        }

        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        bundleComponent.writeComboConfigMetafield(
                req.getShopName(),
                auth.getShopDomain(),
                auth.getAccessToken(),
                req.getProductId(),
                config.toJSONString());

        return new ShopComboSaveVO()
                .setProductId(numericId(req.getProductId()))
                .setKind(kind)
                .setSaved(true)
                .setCheckoutPending(true)
                .setMessage("Combo saved on product. Checkout discount applies after Function is deployed.");
    }

    /**
     * Gift rule on trigger product (separate entry from kit composer).
     * Phase 1: persist metafield only; free gift at checkout is Function Phase 2.
     */
    public ShopGiftSaveVO saveGiftRule(ShopGiftSaveReq req) {
        if (req == null || StringUtils.isAnyBlank(req.getShopName(), req.getProductId(), req.getGiftProductId())) {
            throw new CustomException("shopName, productId and giftProductId required");
        }
        String kind = StringUtils.defaultIfBlank(req.getKind(), "qty_gift").trim().toLowerCase();
        if (!"qty_gift".equals(kind)) {
            throw new CustomException("kind must be qty_gift");
        }
        if (!shopProductBindingRepository.hasActiveItemBinding(
                req.getShopName(), numericId(req.getProductId()))) {
            throw new CustomException("Trigger product must have an ACTIVE source binding");
        }
        if (!shopProductBindingRepository.hasActiveItemBinding(
                req.getShopName(), numericId(req.getGiftProductId()))) {
            throw new CustomException("Gift product must have an ACTIVE source binding");
        }
        if (StringUtils.isBlank(req.getGiftVariantId())) {
            throw new CustomException("giftVariantId required");
        }
        int minQty = req.getMinQty() == null ? 1 : Math.max(1, req.getMinQty());
        int giftQty = req.getGiftQty() == null ? 1 : Math.max(1, req.getGiftQty());

        JSONObject rule = new JSONObject();
        rule.put("kind", kind);
        rule.put("status", "ACTIVE");
        rule.put("schemaVersion", 1);
        rule.put("triggerProductId", numericId(req.getProductId()));
        rule.put("minQty", minQty);
        rule.put("giftProductId", numericId(req.getGiftProductId()));
        rule.put("giftVariantId", numericId(req.getGiftVariantId()));
        rule.put("giftQty", giftQty);
        rule.put("label", StringUtils.defaultIfBlank(req.getLabel(), ""));

        ShopifyStoreAuth auth = requireAuth(req.getShopName());
        bundleComponent.enrichGiftRuleDisplay(
                req.getShopName(), auth.getShopDomain(), auth.getAccessToken(), rule);
        bundleComponent.writeGiftRuleMetafield(
                req.getShopName(),
                auth.getShopDomain(),
                auth.getAccessToken(),
                req.getProductId(),
                rule.toJSONString());

        return new ShopGiftSaveVO()
                .setProductId(numericId(req.getProductId()))
                .setKind(kind)
                .setSaved(true)
                .setCheckoutPending(false)
                .setMessage("Gift rule saved. Add the Free gift Theme Block on the trigger PDP; Discount Function applies 100% when gift is in cart.");
    }

    public ShopBundleVO dissolve(String shopName, Long bundleId) {
        if (bundleId == null || StringUtils.isBlank(shopName)) {
            throw new CustomException("shopName and bundleId required");
        }
        ShopProductBundle row = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new CustomException("Bundle not found"));
        if (!shopName.equals(row.getShopName())) {
            throw new CustomException("Bundle shop mismatch");
        }
        if (row.getManagedByApp() != 1) {
            throw new CustomException("Bundle is managed by another app");
        }
        ShopifyStoreAuth auth = requireAuth(shopName);
        if (StringUtils.isNotBlank(row.getParentProductId())) {
            try {
                bundleComponent.clearKitMarkers(
                        shopName, auth.getShopDomain(), auth.getAccessToken(), row.getParentProductId());
            } catch (Exception ignored) {
                /* best-effort before delete */
            }
            try {
                bundleComponent.deleteParentProduct(
                        shopName, auth.getShopDomain(), auth.getAccessToken(), row.getParentProductId());
            } catch (Exception e) {
                if (isShopifyProductAlreadyGone(e)) {
                    log.info("Bundle parent already gone on Shopify shop={} id={} parent={}",
                            shopName, bundleId, row.getParentProductId());
                } else {
                    log.warn("Bundle parent delete on Shopify failed shop={} id={}: {}",
                            shopName, bundleId, e.getMessage());
                    throw e instanceof CustomException ce
                            ? ce
                            : new CustomException(
                                    "Failed to delete Shopify parent product; dissolve aborted: "
                                            + e.getMessage());
                }
            }
        }
        bundleRepository.updateStatus(bundleId, ShopBundleStatus.DISSOLVED, "Dissolved from App");
        row.setStatus(ShopBundleStatus.DISSOLVED);
        row.setErrorMessage("Dissolved from App");
        return toVo(row);
    }

    /** True when Shopify delete failed because the parent is already missing. */
    private static boolean isShopifyProductAlreadyGone(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("not found")
                || msg.contains("does not exist")
                || msg.contains("could not find")
                || msg.contains("no product")
                || msg.contains("product does not exist");
    }

    private void afterActiveWrite(String shopName, ShopifyStoreAuth auth, ShopProductBundle row,
                                  BigDecimal parentPrice, BigDecimal discountPercent) {
        if (row.getStatus() != ShopBundleStatus.ACTIVE) return;
        if (parentPrice != null
                && StringUtils.isNotBlank(row.getParentProductId())
                && StringUtils.isNotBlank(row.getParentVariantId())) {
            try {
                bundleComponent.updateParentVariantPrice(
                        shopName,
                        auth.getShopDomain(),
                        auth.getAccessToken(),
                        row.getParentProductId(),
                        row.getParentVariantId(),
                        parentPrice);
                row.setParentPrice(parentPrice);
            } catch (Exception priceErr) {
                log.warn("Bundle parent price update skipped shop={} id={}: {}",
                        shopName, row.getId(), priceErr.getMessage());
            }
        }
        if (StringUtils.isNotBlank(row.getParentProductId())) {
            bundleComponent.setBundleDiscountMetafield(
                    shopName, auth.getShopDomain(), auth.getAccessToken(),
                    row.getParentProductId(), discountPercent);
            List<ShopifyProductBundleComponent.ComponentSpec> specs = specsFromSnapshot(row);
            bundleComponent.enrichParentMerchandise(
                    shopName,
                    auth.getShopDomain(),
                    auth.getAccessToken(),
                    row.getParentProductId(),
                    row.getContextProductId(),
                    row.getParentTitle(),
                    specs);
        }
    }

    private static List<ShopifyProductBundleComponent.ComponentSpec> specsFromSnapshot(
            ShopProductBundle row) {
        List<ShopifyProductBundleComponent.ComponentSpec> specs = new ArrayList<>();
        for (ShopBundleVO.ComponentVO c : parseComponents(row.getComponentsJson())) {
            if (c == null || StringUtils.isBlank(c.getProductId())) continue;
            specs.add(new ShopifyProductBundleComponent.ComponentSpec(
                    c.getProductId(),
                    Math.max(1, c.getQuantity()),
                    c.getVariantId()));
        }
        return specs;
    }

    private JSONArray snapshotComponents(String shopName, ShopifyStoreAuth auth,
                                         List<ShopifyProductBundleComponent.ComponentSpec> specs) {
        JSONArray snapshot = new JSONArray();
        for (ShopifyProductBundleComponent.ComponentSpec spec : specs) {
            JSONObject c = new JSONObject();
            c.put("productId", numericId(spec.productId()));
            c.put("quantity", spec.quantity());
            if (StringUtils.isNotBlank(spec.variantId())) {
                c.put("variantId", numericId(spec.variantId()));
            }
            try {
                JSONObject p = bundleComponent.fetchProductOptions(
                        shopName, auth.getShopDomain(), auth.getAccessToken(),
                        ShopifyProductBundleComponent.toProductGid(spec.productId()));
                if (p != null) c.put("title", p.getString("title"));
            } catch (Exception ignored) {
                /* title optional */
            }
            snapshot.add(c);
        }
        return snapshot;
    }

    private void assertAllComponentsBound(String shopName,
                                           List<ShopifyProductBundleComponent.ComponentSpec> specs) {
        List<String> missing = new ArrayList<>();
        for (ShopifyProductBundleComponent.ComponentSpec spec : specs) {
            if (!shopProductBindingRepository.hasActiveItemBinding(shopName, numericId(spec.productId()))) {
                missing.add(numericId(spec.productId()));
            }
        }
        if (!missing.isEmpty()) {
            throw new CustomException(
                    "All bundle components must have an ACTIVE source binding. Unbound: "
                            + String.join(", ", missing));
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

    private List<ShopifyProductBundleComponent.ComponentSpec> normalizeComponents(
            String contextProductId,
            String contextVariantId,
            List<ShopBundleCreateReq.ComponentInput> components) {
        Map<String, ShopifyProductBundleComponent.ComponentSpec> byId = new LinkedHashMap<>();
        String contextId = numericId(contextProductId);
        String contextVariant = StringUtils.isBlank(contextVariantId)
                ? null
                : numericId(contextVariantId);
        byId.put(contextId, new ShopifyProductBundleComponent.ComponentSpec(
                contextId, 1, contextVariant));
        if (components != null) {
            for (ShopBundleCreateReq.ComponentInput c : components) {
                if (c == null || StringUtils.isBlank(c.getProductId())) continue;
                String id = numericId(c.getProductId());
                int q = c.getQuantity() == null ? 1 : Math.max(1, c.getQuantity());
                String variantId = StringUtils.isBlank(c.getVariantId()) ? null : numericId(c.getVariantId());
                // Context is already seeded at qty 1 — do not double-count if FE also sends it.
                if (id.equals(contextId)) {
                    byId.put(id, new ShopifyProductBundleComponent.ComponentSpec(
                            id,
                            1,
                            variantId != null ? variantId : contextVariant));
                    continue;
                }
                ShopifyProductBundleComponent.ComponentSpec existing = byId.get(id);
                if (existing == null) {
                    byId.put(id, new ShopifyProductBundleComponent.ComponentSpec(id, q, variantId));
                } else {
                    byId.put(id, new ShopifyProductBundleComponent.ComponentSpec(
                            id, existing.quantity() + q,
                            variantId != null ? variantId : existing.variantId()));
                }
            }
        }
        if (byId.size() < 2) {
            throw new CustomException("Select at least one additional product as a bundle component");
        }
        return new ArrayList<>(byId.values());
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
        if (asParent || card.getComponentProductIds() == null || card.getComponentProductIds().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (ShopBundleVO.ComponentVO c : components) {
                if (c != null && StringUtils.isNotBlank(c.getProductId())) {
                    ids.add(numericId(c.getProductId()));
                }
            }
            card.setComponentProductIds(ids);
            card.setComponentCount(ids.isEmpty() ? components.size() : ids.size());
        }
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
                .setDiscountPercent(row.getDiscountPercent())
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
                        .setTitle(o.getString("title"))
                        .setVariantId(numericId(o.getString("variantId"))));
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
