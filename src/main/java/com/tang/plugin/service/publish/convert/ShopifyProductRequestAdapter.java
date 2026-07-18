package com.tang.plugin.service.publish.convert;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.bo.product.ShopifyProductMirror;
import com.tang.plugin.domain.entity.product.ProductAttribute;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.domain.entity.product.ThirdPlatformProductMedia;
import com.tang.plugin.domain.entity.product.ThirdPlatformSku;
import com.tang.plugin.domain.enums.WeightUnit;
import com.tang.plugin.enums.PluginType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts raw Shopify product JSON into the internal standardized mirror.
 * Only this adapter may read raw JSON; downstream handles internal models only.
 */
@Slf4j
@Component
public class ShopifyProductRequestAdapter {

    private static final String SHOP_TYPE = PluginType.SHOPIFY.getCode();

    /**
     * Convert one Shopify product node. Returns null if the node has no usable id.
     */
    public ShopifyProductMirror convert(JSONObject node, String shopName, String currency, BigDecimal exchangeRate) {
        if (node == null) {
            return null;
        }
        String productId = node.getString("id");
        if (StringUtils.isBlank(productId)) {
            log.warn("Shopify product missing id, skip shopName={}", shopName);
            return null;
        }
        BigDecimal rate = exchangeRate == null ? BigDecimal.ONE : exchangeRate;

        ThirdPlatformProduct product = new ThirdPlatformProduct()
                .setShopName(shopName)
                .setShopType(SHOP_TYPE)
                .setThirdPlatformItemId(productId)
                .setHandle(node.getString("handle"))
                .setTitle(node.getString("title"))
                .setDescription(node.getString("descriptionHtml"))
                .setStatus(node.getString("status"))
                .setCurrency(currency)
                .setUpdatedAt(parseInstant(node.getString("updatedAt")))
                .setPrimaryImageUrl(extractFeaturedImage(node))
                .setDelFlag(0);

        product.setProductAttributeList(convertOptions(node));
        product.setMediaList(convertMedia(node, shopName, productId));

        List<ThirdPlatformSku> skuList = convertVariants(node, shopName, productId, rate);
        applyExtremums(product, skuList);

        ShopifyProductMirror mirror = new ShopifyProductMirror();
        mirror.setProduct(product);
        mirror.setSkuList(skuList);
        return mirror;
    }

    private String extractFeaturedImage(JSONObject node) {
        JSONObject featured = node.getJSONObject("featuredImage");
        return featured == null ? null : featured.getString("url");
    }

    private List<ProductAttribute> convertOptions(JSONObject node) {
        List<ProductAttribute> result = new ArrayList<>();
        JSONArray options = node.getJSONArray("options");
        if (CollectionUtils.isEmpty(options)) {
            return result;
        }
        for (int i = 0; i < options.size(); i++) {
            JSONObject opt = options.getJSONObject(i);
            if (opt == null) {
                continue;
            }
            String name = opt.getString("name");
            Integer position = opt.getInteger("position");
            JSONArray values = opt.getJSONArray("values");
            if (CollectionUtils.isEmpty(values)) {
                result.add(new ProductAttribute().setName(name).setValue(null).setPosition(position));
                continue;
            }
            for (int v = 0; v < values.size(); v++) {
                result.add(new ProductAttribute()
                        .setName(name)
                        .setValue(values.getString(v))
                        .setPosition(position));
            }
        }
        return result;
    }

    private List<ThirdPlatformProductMedia> convertMedia(JSONObject node, String shopName, String productId) {
        List<ThirdPlatformProductMedia> result = new ArrayList<>();
        JSONObject media = node.getJSONObject("media");
        if (media == null) {
            return result;
        }
        JSONArray edges = media.getJSONArray("edges");
        if (CollectionUtils.isEmpty(edges)) {
            return result;
        }
        int position = 0;
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            JSONObject mediaNode = edge == null ? null : edge.getJSONObject("node");
            if (mediaNode == null) {
                continue;
            }
            String mediaId = mediaNode.getString("id");
            if (StringUtils.isBlank(mediaId)) {
                continue;
            }
            JSONObject image = mediaNode.getJSONObject("image");
            String url = image == null ? null : image.getString("url");
            String alt = image == null ? null : image.getString("altText");
            result.add(new ThirdPlatformProductMedia()
                    .setShopName(shopName)
                    .setShopType(SHOP_TYPE)
                    .setThirdPlatformItemId(productId)
                    .setMediaId(mediaId)
                    .setUrl(url)
                    .setAlt(alt)
                    .setPosition(position++)
                    .setDelFlag(0));
        }
        return result;
    }

    private List<ThirdPlatformSku> convertVariants(JSONObject node, String shopName, String productId, BigDecimal rate) {
        List<ThirdPlatformSku> result = new ArrayList<>();
        JSONObject variants = node.getJSONObject("variants");
        if (variants == null) {
            return result;
        }
        JSONArray edges = variants.getJSONArray("edges");
        if (CollectionUtils.isEmpty(edges)) {
            return result;
        }
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            JSONObject variant = edge == null ? null : edge.getJSONObject("node");
            if (variant == null) {
                continue;
            }
            String variantId = variant.getString("id");
            if (StringUtils.isBlank(variantId)) {
                continue;
            }
            BigDecimal price = parsePrice(variant.getString("price"));
            Double weightGrams = extractWeightGrams(variant, shopName, productId);

            ThirdPlatformSku sku = new ThirdPlatformSku()
                    .setShopName(shopName)
                    .setShopType(SHOP_TYPE)
                    .setThirdPlatformItemId(productId)
                    .setThirdPlatformSkuId(variantId)
                    .setSku(variant.getString("sku"))
                    .setTitle(variant.getString("title"))
                    .setPrice(price)
                    .setPriceLocal(price == null ? null : price.multiply(rate).setScale(4, RoundingMode.HALF_UP))
                    .setWeightGrams(weightGrams)
                    .setBarcode(variant.getString("barcode"))
                    .setInventoryQuantity(variant.getInteger("inventoryQuantity"))
                    .setImageUrl(extractVariantImage(variant))
                    .setPosition(variant.getInteger("position"))
                    .setDelFlag(0);

            applySelectedOptions(sku, variant);
            result.add(sku);
        }
        return result;
    }

    private String extractVariantImage(JSONObject variant) {
        JSONObject image = variant.getJSONObject("image");
        return image == null ? null : image.getString("url");
    }

    private void applySelectedOptions(ThirdPlatformSku sku, JSONObject variant) {
        JSONArray selected = variant.getJSONArray("selectedOptions");
        if (CollectionUtils.isEmpty(selected)) {
            return;
        }
        for (int i = 0; i < selected.size(); i++) {
            JSONObject opt = selected.getJSONObject(i);
            if (opt == null) {
                continue;
            }
            String value = opt.getString("value");
            switch (i) {
                case 0 -> sku.setOption1(value);
                case 1 -> sku.setOption2(value);
                case 2 -> sku.setOption3(value);
                default -> { /* P1 keeps at most 3 options */ }
            }
        }
    }

    private Double extractWeightGrams(JSONObject variant, String shopName, String productId) {
        JSONObject inventoryItem = variant.getJSONObject("inventoryItem");
        JSONObject measurement = inventoryItem == null ? null : inventoryItem.getJSONObject("measurement");
        JSONObject weight = measurement == null ? null : measurement.getJSONObject("weight");
        if (weight == null) {
            return null;
        }
        Double value = weight.getDouble("value");
        String unit = weight.getString("unit");
        if (value == null || StringUtils.isBlank(unit)) {
            return null;
        }
        try {
            WeightUnit weightUnit = WeightUnit.valueOf(unit.trim().toUpperCase());
            return WeightUnit.convertTo(value, weightUnit);
        } catch (IllegalArgumentException e) {
            log.warn("Shopify unknown weight unit={} shopName={} productId={}", unit, shopName, productId);
            return null;
        }
    }

    private void applyExtremums(ThirdPlatformProduct product, List<ThirdPlatformSku> skuList) {
        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;
        BigDecimal minPriceLocal = null;
        BigDecimal maxPriceLocal = null;
        Double minWeight = null;
        Double maxWeight = null;
        for (ThirdPlatformSku sku : skuList) {
            BigDecimal price = sku.getPrice();
            if (price != null) {
                minPrice = (minPrice == null || price.compareTo(minPrice) < 0) ? price : minPrice;
                maxPrice = (maxPrice == null || price.compareTo(maxPrice) > 0) ? price : maxPrice;
            }
            BigDecimal priceLocal = sku.getPriceLocal();
            if (priceLocal != null) {
                minPriceLocal = (minPriceLocal == null || priceLocal.compareTo(minPriceLocal) < 0) ? priceLocal : minPriceLocal;
                maxPriceLocal = (maxPriceLocal == null || priceLocal.compareTo(maxPriceLocal) > 0) ? priceLocal : maxPriceLocal;
            }
            Double weight = sku.getWeightGrams();
            if (weight != null) {
                minWeight = (minWeight == null || weight < minWeight) ? weight : minWeight;
                maxWeight = (maxWeight == null || weight > maxWeight) ? weight : maxWeight;
            }
        }
        product.setMinPrice(minPrice)
                .setMaxPrice(maxPrice)
                .setMinPriceLocal(minPriceLocal)
                .setMaxPriceLocal(maxPriceLocal)
                .setMinWeightGrams(minWeight)
                .setMaxWeightGrams(maxWeight);
    }

    private BigDecimal parsePrice(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Shopify invalid price value={}", raw);
            return null;
        }
    }

    private Instant parseInstant(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            log.warn("Shopify invalid updatedAt value={}", raw);
            return null;
        }
    }
}
