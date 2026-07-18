package com.tang.plugin.service.publish;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.catalog.TangbuyCatalogProduct;
import com.tang.plugin.domain.dto.publish.PublishResultVO;
import com.tang.plugin.domain.dto.publish.ShopifyCreateProductResult;
import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import com.tang.plugin.domain.entity.publish.ProductPublishRecord;
import com.tang.plugin.domain.entity.user.ShopifyStoreAuth;
import com.tang.plugin.enums.publish.ProductPublishStatus;
import com.tang.plugin.repository.ShopifyStoreAuthRepository;
import com.tang.plugin.service.catalog.TangbuyCatalogService;
import com.tang.plugin.service.pricing.PriceCalculator;
import com.tang.plugin.service.pricing.PricingTemplateService;
import com.tang.plugin.service.publish.component.shopify.ShopifyProductPublishComponent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Orchestrates publishing one Tangbuy catalog candidate into a new sellable Shopify product,
 * stitching pricing (M1-2), the publish ledger state machine (M1-3), and the real Shopify create
 * (M1-4). Single candidate, no batch. Idempotent by (shop, candidate) via the ledger.
 */
@Slf4j
@Service
public class CatalogPublishService {

    private static final String REQUIRED_SCOPE = "write_products";
    private static final int SKU_MAX_LENGTH = 255;

    @Resource
    private TangbuyCatalogService tangbuyCatalogService;
    @Resource
    private PricingTemplateService pricingTemplateService;
    @Resource
    private PriceCalculator priceCalculator;
    @Resource
    private ProductPublishRecordService productPublishRecordService;
    @Resource
    private ShopifyStoreAuthRepository shopifyStoreAuthRepository;
    @Resource
    private ShopifyProductPublishComponent shopifyProductPublishComponent;

    public PublishResultVO publish(String shopName, String candidateId) {
        if (StringUtils.isBlank(shopName) || StringUtils.isBlank(candidateId)) {
            throw new CustomException("publish requires shopName and candidateId");
        }
        TangbuyCatalogProduct candidate = tangbuyCatalogService.findById(candidateId)
                .orElseThrow(() -> new CustomException("candidate not found, candidateId=" + candidateId));
        if (StringUtils.isBlank(candidate.getTitle())) {
            throw new CustomException("candidate has no title, candidateId=" + candidateId);
        }

        PricingTemplate template = pricingTemplateService.getEffective(shopName);
        BigDecimal salePrice = priceCalculator.calculate(candidate.getPrice(), template);

        ProductPublishRecord record = productPublishRecordService.getOrCreate(
                snapshot(shopName, candidate, salePrice, template.getTargetCurrency()));

        // Repeat-trigger semantics on the current ledger state.
        if (record.getPublishStatus() == ProductPublishStatus.PUBLISHED) {
            return shortCircuit(record, "already published");
        }
        if (record.getPublishStatus() == ProductPublishStatus.PUBLISHING) {
            return inProgress(record);
        }

        // write_products precheck BEFORE moving state (keeps attempts/state clean when unauthorized).
        ShopifyStoreAuth auth = shopifyStoreAuthRepository.findActiveByShopName(shopName)
                .orElseThrow(() -> new CustomException("shop not authorized (no ACTIVE auth), shopName=" + shopName));
        if (!scopeContains(auth.getScope(), REQUIRED_SCOPE)) {
            throw new CustomException("shop missing " + REQUIRED_SCOPE
                    + " scope; re-authorize via /install, shopName=" + shopName);
        }
        // A sellable product needs a price.
        if (salePrice == null) {
            throw new CustomException("missing procurement price, cannot price candidate=" + candidateId);
        }

        boolean started = productPublishRecordService.markPublishing(record.getId());
        if (!started) {
            // Concurrent transition; re-read and mirror the repeat semantics.
            ProductPublishRecord latest = productPublishRecordService
                    .getOrCreate(snapshot(shopName, candidate, salePrice, template.getTargetCurrency()));
            return latest.getPublishStatus() == ProductPublishStatus.PUBLISHED
                    ? shortCircuit(latest, "already published")
                    : inProgress(latest);
        }

        try {
            ShopifyCreateProductResult created = shopifyProductPublishComponent.createSellableProduct(
                    shopName, auth.getShopDomain(), auth.getAccessToken(),
                    candidate.getTitle(), salePrice, sanitizeSku(candidateId), candidate.getBarcode(),
                    buildDescriptionHtml(candidate), candidate.getImageUrl());

            productPublishRecordService.markPublished(record.getId(), created.getProductId(),
                    created.getHandle(), created.getVariantId(), created.getInventoryItemId());

            return new PublishResultVO()
                    .setStatus("OK")
                    .setPublishStatus(ProductPublishStatus.PUBLISHED.name())
                    .setCandidateId(candidateId)
                    .setShopifyProductId(created.getProductId())
                    .setShopifyProductHandle(created.getHandle())
                    .setShopifyVariantId(created.getVariantId())
                    .setSalePrice(salePrice)
                    .setTargetCurrency(template.getTargetCurrency())
                    .setMessage("published");
        } catch (Exception e) {
            productPublishRecordService.markFailed(record.getId(), e.getMessage());
            log.error("Publish failed shopName={} candidateId={}", shopName, candidateId, e);
            throw new CustomException("publish failed, candidateId=" + candidateId + ", cause=" + e.getMessage());
        }
    }

    private ProductPublishRecord snapshot(String shopName, TangbuyCatalogProduct candidate,
                                          BigDecimal salePrice, String targetCurrency) {
        return new ProductPublishRecord()
                .setShopName(shopName)
                .setCandidateId(candidate.getCandidateId())
                .setTangbuyProductId(candidate.getTangbuyProductId())
                .setOfferId1688(candidate.getOfferId1688())
                .setSkuId(candidate.getSkuId())
                .setTitle(candidate.getTitle())
                .setSourcePrice(candidate.getPrice())
                .setSourceCurrency(candidate.getCurrency())
                .setSalePrice(salePrice)
                .setTargetCurrency(targetCurrency);
    }

    private PublishResultVO shortCircuit(ProductPublishRecord record, String message) {
        return new PublishResultVO()
                .setStatus("OK")
                .setPublishStatus(ProductPublishStatus.PUBLISHED.name())
                .setCandidateId(record.getCandidateId())
                .setShopifyProductId(record.getShopifyProductId())
                .setShopifyProductHandle(record.getShopifyProductHandle())
                .setShopifyVariantId(record.getShopifyVariantId())
                .setSalePrice(record.getSalePrice())
                .setTargetCurrency(record.getTargetCurrency())
                .setMessage(message);
    }

    private PublishResultVO inProgress(ProductPublishRecord record) {
        return new PublishResultVO()
                .setStatus("OK")
                .setPublishStatus(ProductPublishStatus.PUBLISHING.name())
                .setCandidateId(record.getCandidateId())
                .setSalePrice(record.getSalePrice())
                .setTargetCurrency(record.getTargetCurrency())
                .setMessage("publish in progress");
    }

    private static boolean scopeContains(String scope, String required) {
        if (StringUtils.isBlank(scope)) {
            return false;
        }
        return Arrays.stream(scope.split(","))
                .map(String::trim)
                .anyMatch(required::equals);
    }

    /**
     * Minimal product description from the candidate: 规格 (sku_attr), 供应商 (supplier_shop),
     * 货源平台 (upstream_platform). Values are HTML-escaped. Returns null when all fields are blank.
     */
    private static String buildDescriptionHtml(TangbuyCatalogProduct c) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "规格", c.getSkuAttr());
        appendLine(sb, "供应商", c.getSupplierShop());
        appendLine(sb, "货源平台", c.getUpstreamPlatform());
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append("<p>").append(escapeHtml(label)).append("：")
                    .append(escapeHtml(value.trim())).append("</p>");
        }
    }

    /** Minimal HTML escaping for plain-text values embedded into descriptionHtml. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Minimal SKU cleansing: trim, strip whitespace/control chars, cap length. */
    private static String sanitizeSku(String candidateId) {
        if (candidateId == null) {
            return null;
        }
        String cleaned = candidateId.trim().replaceAll("\\s+", "");
        if (cleaned.length() > SKU_MAX_LENGTH) {
            cleaned = cleaned.substring(0, SKU_MAX_LENGTH);
        }
        return cleaned;
    }
}
