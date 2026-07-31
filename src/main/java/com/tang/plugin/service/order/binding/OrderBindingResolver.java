package com.tang.plugin.service.order.binding;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.tang.plugin.domain.dto.bundle.ShopBundleVO;
import com.tang.plugin.domain.dto.match.SkuBindingView;
import com.tang.plugin.domain.dto.order.OrderBindingSummary;
import com.tang.plugin.domain.entity.bundle.ShopProductBundle;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.domain.entity.order.ExternalOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.repository.bundle.ShopProductBundleRepository;
import com.tang.plugin.service.bundle.component.ShopifyProductBundleComponent;
import com.tang.plugin.service.match.ProductBindingQueryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves order lines against active SKU bindings.
 * Fixed-bundle parent lines are expanded into component purchase lines first.
 */
@Slf4j
@Component
public class OrderBindingResolver {

    @Resource
    private ProductBindingQueryService productBindingQueryService;
    @Resource
    private ShopProductBundleRepository shopProductBundleRepository;

    public OrderBindingSummary resolve(String shopName, ExternalOrder externalOrder) {
        String orderId = externalOrder == null ? null : externalOrder.getOrderId();
        OrderBindingSummary summary = new OrderBindingSummary()
                .setShopName(shopName)
                .setOrderId(orderId);

        if (externalOrder == null || CollectionUtils.isEmpty(externalOrder.getLines())) {
            log.info("Order binding summary shopName={} orderId={} total=0 bound=0 unbound=0",
                    shopName, orderId);
            return summary;
        }

        List<ExternalOrderLine> expanded = expandBundleParents(shopName, externalOrder.getLines());
        externalOrder.setLines(expanded);

        int bound = 0;
        int unbound = 0;
        for (ExternalOrderLine line : expanded) {
            if (line == null) continue;
            if (resolveLine(shopName, orderId, line)) {
                bound++;
            } else {
                unbound++;
            }
        }

        summary.setTotal(bound + unbound).setBound(bound).setUnbound(unbound);
        log.info("Order binding summary shopName={} orderId={} total={} bound={} unbound={}",
                shopName, orderId, summary.getTotal(), bound, unbound);
        return summary;
    }

    private List<ExternalOrderLine> expandBundleParents(String shopName, List<ExternalOrderLine> lines) {
        List<ExternalOrderLine> out = new ArrayList<>();
        for (ExternalOrderLine line : lines) {
            if (line == null) continue;
            Optional<ShopProductBundle> bundle = Optional.empty();
            if (StringUtils.isNotBlank(line.getOuterVariantId())) {
                bundle = shopProductBundleRepository.findActiveByParentVariant(
                        shopName, line.getOuterVariantId());
            }
            if (bundle.isEmpty()) {
                out.add(line);
                continue;
            }
            List<ShopBundleVO.ComponentVO> components = parseComponents(bundle.get().getComponentsJson());
            if (components.isEmpty()) {
                out.add(line);
                continue;
            }
            int parentQty = line.getQuantity() == null || line.getQuantity() < 1 ? 1 : line.getQuantity();
            int totalUnits = components.stream().mapToInt(c -> Math.max(1, c.getQuantity())).sum();
            if (totalUnits < 1) totalUnits = components.size();
            BigDecimal parentUnit = line.getPrice() == null ? BigDecimal.ZERO : line.getPrice();
            for (ShopBundleVO.ComponentVO c : components) {
                int compQty = Math.max(1, c.getQuantity()) * parentQty;
                BigDecimal share = parentUnit
                        .multiply(BigDecimal.valueOf(Math.max(1, c.getQuantity())))
                        .divide(BigDecimal.valueOf(totalUnits), 4, RoundingMode.HALF_UP);
                ExternalOrderLine synth = new ExternalOrderLine()
                        .setLineId(line.getLineId() + "::c:" + c.getProductId())
                        .setTitle(StringUtils.defaultIfBlank(c.getTitle(), "Bundle component " + c.getProductId()))
                        .setQuantity(compQty)
                        .setPrice(share)
                        .setImageUrl(line.getImageUrl())
                        .setSku(line.getSku())
                        .setVariantTitle(line.getVariantTitle());
                if (StringUtils.isNotBlank(c.getVariantId())) {
                    synth.setOuterVariantId("gid://shopify/ProductVariant/" + c.getVariantId());
                }
                out.add(synth);
            }
            log.info("Expanded bundle parent line shop={} lineId={} components={}",
                    shopName, line.getLineId(), components.size());
        }
        return out;
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
                        .setProductId(ShopifyProductBundleComponent.numericProductId(o.getString("productId")))
                        .setQuantity(o.getIntValue("quantity", 1))
                        .setTitle(o.getString("title"))
                        .setVariantId(ShopifyProductBundleComponent.numericProductId(o.getString("variantId"))));
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        return list;
    }

    private boolean resolveLine(String shopName, String orderId, ExternalOrderLine line) {
        String variantGid = line.getOuterVariantId();
        if (StringUtils.isBlank(variantGid)) {
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            return false;
        }
        try {
            Optional<SkuBindingView> view = productBindingQueryService.findActiveSkuBinding(shopName, variantGid);
            if (view.isPresent()) {
                SkuBindingView binding = view.get();
                line.setTangbuyProductId(binding.getTangbuyProductId());
                line.setTangbuySkuId(binding.getTangbuySkuId());
                line.setBindingStatus(OrderLineBindingStatus.BOUND);
                return true;
            }
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            return false;
        } catch (Exception e) {
            line.setBindingStatus(OrderLineBindingStatus.UNBOUND);
            log.error("Order line binding query failed, degrade to UNBOUND shopName={} orderId={} outerVariantId={}",
                    shopName, orderId, variantGid, e);
            return false;
        }
    }
}
