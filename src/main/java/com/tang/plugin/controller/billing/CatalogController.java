package com.tang.plugin.controller.billing;

import com.tang.plugin.domain.entity.user.CreditPackage;
import com.tang.plugin.domain.entity.user.SubscriptionPlan;
import com.tang.plugin.dto.billing.BillingDtos.CatalogItem;
import com.tang.plugin.dto.billing.BillingDtos.CatalogResponse;
import com.tang.plugin.repository.CreditPackageRepository;
import com.tang.plugin.repository.SubscriptionPlanRepository;
import com.tang.plugin.service.user.AdminGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品目录接口（§3 / D4）。返回三档商品的常规积分、促销积分与促销是否生效。
 *
 * <p>路径前缀 {@code /api/plugin/billing/**} 已在 {@code JwtAuthFilter} 中受保护。
 */
@RestController
@RequestMapping("/api/plugin/billing")
public class CatalogController {

    @Resource
    private SubscriptionPlanRepository planRepository;

    @Resource
    private CreditPackageRepository packRepository;

    @Resource
    private AdminGuard adminGuard;

    @GetMapping("/catalog/plans")
    public ResponseEntity<CatalogResponse> catalog() {
        Instant now = Instant.now();
        List<CatalogItem> plans = planRepository.listActive().stream()
                .map(p -> toItem(p, "subscription", now))
                .collect(Collectors.toList());
        List<CatalogItem> packages = packRepository.listActive().stream()
                .map(p -> toItem(p, "credit_pack", now))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new CatalogResponse(plans, packages));
    }

    private CatalogItem toItem(SubscriptionPlan p, String kind, Instant now) {
        boolean promo = p.getPromoUntil() != null && p.getPromoUntil().isAfter(now);
        return new CatalogItem(p.getCode(), kind, p.getName(), p.getPriceUsdCents(),
                p.getCreditsNormal(), p.getCreditsPromo(), promo, p.getDurationDays());
    }

    private CatalogItem toItem(CreditPackage p, String kind, Instant now) {
        boolean promo = p.getPromoUntil() != null && p.getPromoUntil().isAfter(now);
        return new CatalogItem(p.getCode(), kind, p.getName(), p.getPriceUsdCents(),
                p.getCreditsNormal(), p.getCreditsPromo(), promo, p.getDurationDays());
    }
}
