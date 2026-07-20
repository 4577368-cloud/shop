package com.tang.plugin.service.pricing;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.pricing.PricingTemplateUpsertRequest;
import com.tang.plugin.domain.dto.pricing.PricingTemplateVO;
import com.tang.plugin.domain.entity.pricing.PricingTemplate;
import com.tang.plugin.repository.PricingTemplateRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Pricing-template read/write orchestration and defaulting. The effective template is the shop's
 * stored row when present, otherwise an in-memory system default (id == null) so recommendations
 * can always produce a sale price. Single-shop, single-template scope (no multi-market).
 */
@Slf4j
@Service
public class PricingTemplateService {

    static final String DEFAULT_SOURCE_CURRENCY = "CNY";
    static final String DEFAULT_TARGET_CURRENCY = "USD";
    static final double DEFAULT_MULTIPLIER = 2.0d;
    static final double DEFAULT_ADDEND = 0d;
    static final String DEFAULT_ROUNDING = RoundingStrategy.HALF_UP.name();
    static final int DEFAULT_DECIMALS = 2;
    /** Placeholder static rate: source units per 1 target (e.g. ~7.2 CNY = 1 USD). No live FX. */
    static final double DEFAULT_EXCHANGE_RATE = 7.2d;

    private static final int MIN_DECIMALS = 0;
    private static final int MAX_DECIMALS = 4;

    @Resource
    private PricingTemplateRepository pricingTemplateRepository;

    /**
     * Effective template for a shop: stored row if present, else the system default. A null/blank
     * shopName is allowed and yields the system default (used when recommendations omit shopName).
     */
    public PricingTemplate getEffective(String shopName) {
        if (StringUtils.isNotBlank(shopName)) {
            return pricingTemplateRepository.findByShop(shopName)
                    .orElseGet(() -> systemDefault(shopName));
        }
        return systemDefault(shopName);
    }

    /** In-memory default (never persisted); id stays null so callers can detect it. */
    public PricingTemplate systemDefault(String shopName) {
        return new PricingTemplate()
                .setShopName(shopName)
                .setSourceCurrency(DEFAULT_SOURCE_CURRENCY)
                .setTargetCurrency(DEFAULT_TARGET_CURRENCY)
                .setExchangeRate(DEFAULT_EXCHANGE_RATE)
                .setMultiplier(DEFAULT_MULTIPLIER)
                .setAddend(DEFAULT_ADDEND)
                .setRoundingStrategy(DEFAULT_ROUNDING)
                .setDecimals(DEFAULT_DECIMALS);
    }

    public PricingTemplate upsert(PricingTemplateUpsertRequest request) {
        if (request == null || StringUtils.isBlank(request.getShopName())) {
            throw new CustomException("pricing template requires shopName");
        }
        if (request.getExchangeRate() == null || request.getExchangeRate() <= 0) {
            throw new CustomException("exchangeRate must be > 0");
        }

        double multiplier = request.getMultiplier() == null ? DEFAULT_MULTIPLIER : request.getMultiplier();
        if (multiplier <= 0) {
            throw new CustomException("multiplier must be > 0");
        }
        double addend = request.getAddend() == null ? DEFAULT_ADDEND : request.getAddend();
        if (addend < 0) {
            throw new CustomException("addend must be >= 0");
        }
        int decimals = request.getDecimals() == null ? DEFAULT_DECIMALS : request.getDecimals();
        if (decimals < MIN_DECIMALS || decimals > MAX_DECIMALS) {
            throw new CustomException("decimals must be within [" + MIN_DECIMALS + "," + MAX_DECIMALS + "]");
        }
        String rounding = StringUtils.isBlank(request.getRoundingStrategy())
                ? DEFAULT_ROUNDING : request.getRoundingStrategy().trim();
        if (!RoundingStrategy.isValid(rounding)) {
            throw new CustomException("invalid roundingStrategy: " + rounding);
        }

        PricingTemplate template = new PricingTemplate()
                .setShopName(request.getShopName().trim())
                .setSourceCurrency(normalizeCurrency(request.getSourceCurrency(), DEFAULT_SOURCE_CURRENCY))
                .setTargetCurrency(normalizeCurrency(request.getTargetCurrency(), DEFAULT_TARGET_CURRENCY))
                .setExchangeRate(request.getExchangeRate())
                .setMultiplier(multiplier)
                .setAddend(addend)
                .setRoundingStrategy(rounding)
                .setDecimals(decimals);

        PricingTemplate saved = pricingTemplateRepository.upsert(template);
        log.info("Pricing template upserted shopName={} rate={} multiplier={} rounding={} decimals={}",
                saved.getShopName(), saved.getExchangeRate(), saved.getMultiplier(),
                saved.getRoundingStrategy(), saved.getDecimals());
        return saved;
    }

    /**
     * Soft-delete the shop's stored template so GET falls back to the system default
     * ({@code isDefault = true}). Used to re-enter first-time pricing setup.
     */
    public PricingTemplate clear(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("pricing template clear requires shopName");
        }
        pricingTemplateRepository.softDeleteByShop(shopName.trim());
        log.info("Pricing template cleared shopName={}", shopName.trim());
        return getEffective(shopName.trim());
    }

    public PricingTemplateVO toVO(PricingTemplate template) {
        return new PricingTemplateVO()
                .setShopName(template.getShopName())
                .setSourceCurrency(template.getSourceCurrency())
                .setTargetCurrency(template.getTargetCurrency())
                .setExchangeRate(template.getExchangeRate())
                .setMultiplier(template.getMultiplier())
                .setAddend(template.getAddend())
                .setRoundingStrategy(template.getRoundingStrategy())
                .setDecimals(template.getDecimals())
                .setDefault(template.getId() == null)
                .setUpdatedAt(template.getUpdatedAt());
    }

    /** Currency: trim, uppercase, must be a 3-letter code; blank falls back to the default. */
    private static String normalizeCurrency(String raw, String fallback) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return fallback;
        }
        value = value.toUpperCase();
        if (value.length() != 3) {
            throw new CustomException("currency must be a 3-letter code: " + raw);
        }
        return value;
    }
}
