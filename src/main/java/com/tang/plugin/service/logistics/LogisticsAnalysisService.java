package com.tang.plugin.service.logistics;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.logistics.CorrectLogisticsTypeRequest;
import com.tang.plugin.domain.dto.logistics.LogisticsAnalysisVO;
import com.tang.plugin.domain.dto.logistics.LogisticsTypeCountVO;
import com.tang.plugin.domain.dto.logistics.ProductLogisticsProfileVO;
import com.tang.plugin.domain.entity.logistics.ProductLogisticsProfile;
import com.tang.plugin.domain.entity.match.ShopProductBinding;
import com.tang.plugin.domain.entity.product.ThirdPlatformProduct;
import com.tang.plugin.enums.logistics.ClassifySource;
import com.tang.plugin.enums.logistics.LogisticsType;
import com.tang.plugin.repository.ProductLogisticsProfileRepository;
import com.tang.plugin.repository.ShopProductBindingRepository;
import com.tang.plugin.repository.ThirdPlatformProductRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the shop logistics analysis: bound products only → rule/keyword classify → persist profiles
 * (preserving USER overrides) → distribution summary for the /logistics workbench.
 */
@Slf4j
@Service
public class LogisticsAnalysisService {

    @Resource
    private ShopProductBindingRepository shopProductBindingRepository;
    @Resource
    private ThirdPlatformProductRepository thirdPlatformProductRepository;
    @Resource
    private ProductLogisticsProfileRepository productLogisticsProfileRepository;
    @Resource
    private LogisticsTypeClassifier logisticsTypeClassifier;

    /**
     * Re-run classification for all bound products (USER-reviewed rows are left intact unless
     * {@code force} is true). Returns a ready-to-render analysis VO.
     */
    public LogisticsAnalysisVO analyze(String shopName, boolean force) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("logistics analysis requires shopName");
        }

        List<ShopProductBinding> bindings = shopProductBindingRepository.listBindableByShop(shopName);
        Set<String> boundItemIds = new LinkedHashSet<>();
        for (ShopProductBinding b : bindings) {
            if (StringUtils.isNotBlank(b.getThirdPlatformItemId())) {
                boundItemIds.add(b.getThirdPlatformItemId());
            }
        }

        List<ThirdPlatformProduct> allProducts = thirdPlatformProductRepository.listByShop(shopName);
        int unbound = 0;
        Map<String, ThirdPlatformProduct> boundProducts = new LinkedHashMap<>();
        for (ThirdPlatformProduct p : allProducts) {
            if (boundItemIds.contains(p.getThirdPlatformItemId())) {
                boundProducts.put(p.getThirdPlatformItemId(), p);
            } else {
                unbound++;
            }
        }
        // Bindings whose product mirror is missing still count as analyze targets with empty title.
        for (String itemId : boundItemIds) {
            boundProducts.putIfAbsent(itemId, new ThirdPlatformProduct()
                    .setThirdPlatformItemId(itemId)
                    .setTitle(null));
        }

        List<ProductLogisticsProfileVO> profiles = new ArrayList<>();
        for (ThirdPlatformProduct product : boundProducts.values()) {
            profiles.add(toVo(ensureProfile(shopName, product, force)));
        }

        Map<LogisticsType, Integer> counts = new EnumMap<>(LogisticsType.class);
        for (LogisticsType t : LogisticsType.values()) {
            counts.put(t, 0);
        }
        List<String> highRisk = new ArrayList<>();
        for (ProductLogisticsProfileVO p : profiles) {
            LogisticsType type = parseType(p.getLogisticsType());
            counts.merge(type, 1, Integer::sum);
            if (type.isHighRisk() && !highRisk.contains(type.name())) {
                highRisk.add(type.name());
            }
        }

        List<LogisticsTypeCountVO> distribution = new ArrayList<>();
        for (LogisticsType t : LogisticsType.values()) {
            int c = counts.getOrDefault(t, 0);
            if (c > 0) {
                distribution.add(new LogisticsTypeCountVO()
                        .setType(t.name())
                        .setLabel(t.getLabel())
                        .setCount(c));
            }
        }

        return new LogisticsAnalysisVO()
                .setShopName(shopName)
                .setStatus("READY")
                .setAnalyzedCount(profiles.size())
                .setSkippedUnboundCount(unbound)
                .setDistribution(distribution)
                .setHighRiskTypes(highRisk)
                .setProfiles(profiles);
    }

    public ProductLogisticsProfileVO correct(CorrectLogisticsTypeRequest request) {
        if (request == null || StringUtils.isAnyBlank(
                request.getShopName(), request.getThirdPlatformItemId(), request.getLogisticsType())) {
            throw new CustomException("correct requires shopName, thirdPlatformItemId and logisticsType");
        }
        LogisticsType type;
        try {
            type = LogisticsType.valueOf(request.getLogisticsType().trim());
        } catch (Exception e) {
            throw new CustomException("invalid logisticsType: " + request.getLogisticsType());
        }

        ProductLogisticsProfile existing = productLogisticsProfileRepository
                .findByItem(request.getShopName(), request.getThirdPlatformItemId())
                .orElse(null);
        String title = existing == null ? null : existing.getTitleSnapshot();
        if (title == null) {
            title = thirdPlatformProductRepository.listByShop(request.getShopName()).stream()
                    .filter(p -> request.getThirdPlatformItemId().equals(p.getThirdPlatformItemId()))
                    .map(ThirdPlatformProduct::getTitle)
                    .findFirst()
                    .orElse(null);
        }

        ProductLogisticsProfile saved = productLogisticsProfileRepository.upsert(
                new ProductLogisticsProfile()
                        .setShopName(request.getShopName())
                        .setThirdPlatformItemId(request.getThirdPlatformItemId())
                        .setTitleSnapshot(title)
                        .setLogisticsType(type.name())
                        .setConfidence(1.0)
                        .setSignalsJson(JSON.toJSONString(List.of("用户修正")))
                        .setClassifySource(ClassifySource.USER.name())
                        .setReviewed(1));
        return toVo(saved);
    }

    private ProductLogisticsProfile ensureProfile(String shopName, ThirdPlatformProduct product, boolean force) {
        ProductLogisticsProfile existing = productLogisticsProfileRepository
                .findByItem(shopName, product.getThirdPlatformItemId())
                .orElse(null);
        if (existing != null
                && !force
                && ClassifySource.USER.name().equals(existing.getClassifySource())) {
            // Keep user override; refresh title snapshot only.
            if (StringUtils.isNotBlank(product.getTitle())
                    && !StringUtils.equals(product.getTitle(), existing.getTitleSnapshot())) {
                existing.setTitleSnapshot(product.getTitle());
                return productLogisticsProfileRepository.upsert(existing);
            }
            return existing;
        }

        LogisticsTypeClassifier.Result result = logisticsTypeClassifier.classify(product.getTitle());
        return productLogisticsProfileRepository.upsert(new ProductLogisticsProfile()
                .setShopName(shopName)
                .setThirdPlatformItemId(product.getThirdPlatformItemId())
                .setTitleSnapshot(product.getTitle())
                .setLogisticsType(result.type().name())
                .setConfidence(result.confidence())
                .setSignalsJson(JSON.toJSONString(result.signals()))
                .setClassifySource(result.source().name())
                .setReviewed(0));
    }

    private static ProductLogisticsProfileVO toVo(ProductLogisticsProfile p) {
        LogisticsType type = parseType(p.getLogisticsType());
        List<String> signals;
        try {
            signals = JSON.parseArray(p.getSignalsJson(), String.class);
            if (signals == null) {
                signals = List.of();
            }
        } catch (Exception e) {
            signals = List.of();
        }
        return new ProductLogisticsProfileVO()
                .setThirdPlatformItemId(p.getThirdPlatformItemId())
                .setTitle(p.getTitleSnapshot())
                .setLogisticsType(type.name())
                .setLogisticsTypeLabel(type.getLabel())
                .setConfidence(p.getConfidence() == null ? 0 : p.getConfidence())
                .setSignals(signals)
                .setClassifySource(p.getClassifySource())
                .setReviewed(p.getReviewed() != null && p.getReviewed() == 1);
    }

    private static LogisticsType parseType(String raw) {
        try {
            return LogisticsType.valueOf(raw);
        } catch (Exception e) {
            return LogisticsType.OTHER;
        }
    }
}
