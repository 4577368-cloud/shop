package com.tang.plugin.service.logistics;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.logistics.LogisticsDeclareConfigDTO;
import com.tang.plugin.domain.dto.logistics.LogisticsTemplateUpsertRequest;
import com.tang.plugin.domain.dto.logistics.LogisticsTemplateVO;
import com.tang.plugin.domain.dto.logistics.MarketSelectionDTO;
import com.tang.plugin.domain.entity.logistics.LogisticsTemplate;
import com.tang.plugin.enums.logistics.PackagingType;
import com.tang.plugin.enums.logistics.SpeedPreference;
import com.tang.plugin.repository.LogisticsTemplateRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One logistics strategy template per shop. Defaults are in-memory until the merchant saves.
 * Declare/tax fields align with tang-plugin LogisticsTemplateConfigVO (lite subset).
 */
@Slf4j
@Service
public class LogisticsTemplateService {

    private static final String DEFAULT_PACKAGING = PackagingType.MINIMAL.name();
    /** Legacy column only — speed preference is no longer part of the product API. */
    private static final String LEGACY_SPEED = SpeedPreference.BALANCED.name();
    private static final String DEFAULT_MARKETS_JSON =
            "[{\"marketGroupId\":\"north_america\",\"countryCodes\":[\"US\"]}]";
    private static final Set<Integer> ALLOWED_REGISTRATION = Set.of(0, 3, 4);
    private static final int MIN_FUZZY_RATIO = 40;

    @Resource
    private LogisticsTemplateRepository logisticsTemplateRepository;

    public LogisticsTemplateVO getEffective(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("logistics template requires shopName");
        }
        return logisticsTemplateRepository.findByShop(shopName)
                .map(row -> toVo(row, false))
                .orElseGet(() -> systemDefault(shopName));
    }

    public LogisticsTemplateVO upsert(LogisticsTemplateUpsertRequest request) {
        if (request == null || StringUtils.isBlank(request.getShopName())) {
            throw new CustomException("logistics template requires shopName");
        }
        String packaging = normalizeEnum(request.getPackaging(), PackagingType.class, DEFAULT_PACKAGING);
        String marketsJson = encodeMarkets(request.getMarkets());
        LogisticsDeclareConfigDTO declare = normalizeDeclare(request.getDeclareConfig());

        LogisticsTemplate saved = logisticsTemplateRepository.upsert(new LogisticsTemplate()
                .setShopName(request.getShopName().trim())
                .setPackaging(packaging)
                .setSpeedPreference(LEGACY_SPEED)
                .setMarketsJson(marketsJson)
                .setDeclareJson(JSON.toJSONString(declare)));
        log.info("Logistics template upserted shopName={} packaging={} markets={} declare={}",
                saved.getShopName(), packaging, marketsJson, saved.getDeclareJson());
        return toVo(saved, false);
    }

    private LogisticsTemplateVO systemDefault(String shopName) {
        return new LogisticsTemplateVO()
                .setShopName(shopName)
                .setPackaging(DEFAULT_PACKAGING)
                .setMarkets(decodeMarkets(DEFAULT_MARKETS_JSON))
                .setDeclareConfig(defaultDeclare())
                .setDefaultTemplate(true)
                .setUpdatedAt(null);
    }

    private LogisticsTemplateVO toVo(LogisticsTemplate row, boolean isDefault) {
        return new LogisticsTemplateVO()
                .setShopName(row.getShopName())
                .setPackaging(row.getPackaging())
                .setMarkets(decodeMarkets(row.getMarketsJson()))
                .setDeclareConfig(decodeDeclare(row.getDeclareJson()))
                .setDefaultTemplate(isDefault)
                .setUpdatedAt(row.getUpdatedAt() == null ? null
                        : DateTimeFormatter.ISO_INSTANT.format(row.getUpdatedAt().atOffset(ZoneOffset.UTC)));
    }

    private static LogisticsDeclareConfigDTO defaultDeclare() {
        return new LogisticsDeclareConfigDTO()
                .setDeclareMode(0)
                .setRegistrationType(0)
                .setDeclareCurrency("USD")
                .setFuzzyRatio(MIN_FUZZY_RATIO)
                .setTax(null)
                .setTaxNo(null);
    }

    private static LogisticsDeclareConfigDTO normalizeDeclare(LogisticsDeclareConfigDTO raw) {
        LogisticsDeclareConfigDTO d = raw == null ? defaultDeclare() : raw;
        int mode = d.getDeclareMode() == null ? 0 : d.getDeclareMode();
        if (mode != 0 && mode != 1) {
            throw new CustomException("invalid declareMode: " + mode);
        }
        int reg = d.getRegistrationType() == null ? 0 : d.getRegistrationType();
        if (!ALLOWED_REGISTRATION.contains(reg)) {
            throw new CustomException("invalid registrationType: " + reg);
        }
        String currency = StringUtils.defaultIfBlank(d.getDeclareCurrency(), "USD")
                .trim().toUpperCase(Locale.ROOT);
        int fuzzy = d.getFuzzyRatio() == null ? MIN_FUZZY_RATIO : d.getFuzzyRatio();
        if (fuzzy < MIN_FUZZY_RATIO) {
            throw new CustomException("fuzzyRatio must be >= " + MIN_FUZZY_RATIO);
        }
        String taxNo = StringUtils.trimToNull(d.getTaxNo());
        if (reg == 4 && StringUtils.isBlank(taxNo)) {
            throw new CustomException("taxNo required for personal IOSS");
        }
        BigDecimal tax = d.getTax();
        if (tax != null && tax.compareTo(BigDecimal.ZERO) < 0) {
            throw new CustomException("tax must be >= 0");
        }
        return new LogisticsDeclareConfigDTO()
                .setDeclareMode(mode)
                .setRegistrationType(reg)
                .setDeclareCurrency(currency)
                .setFuzzyRatio(fuzzy)
                .setTax(tax)
                .setTaxNo(reg == 4 ? taxNo : null);
    }

    private static LogisticsDeclareConfigDTO decodeDeclare(String json) {
        if (StringUtils.isBlank(json)) {
            return defaultDeclare();
        }
        try {
            LogisticsDeclareConfigDTO parsed = JSON.parseObject(json, LogisticsDeclareConfigDTO.class);
            return parsed == null ? defaultDeclare() : normalizeDeclare(parsed);
        } catch (Exception e) {
            log.warn("Invalid declare_json on logistics_template, using defaults: {}", e.getMessage());
            return defaultDeclare();
        }
    }

    private static String encodeMarkets(List<MarketSelectionDTO> markets) {
        if (markets == null || markets.isEmpty()) {
            throw new CustomException("markets must include at least one country");
        }
        List<MarketSelectionDTO> cleaned = new ArrayList<>();
        for (MarketSelectionDTO m : markets) {
            if (m == null || StringUtils.isBlank(m.getMarketGroupId())
                    || m.getCountryCodes() == null || m.getCountryCodes().isEmpty()) {
                continue;
            }
            List<String> codes = m.getCountryCodes().stream()
                    .filter(StringUtils::isNotBlank)
                    .map(c -> c.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
            if (codes.isEmpty()) {
                continue;
            }
            cleaned.add(new MarketSelectionDTO()
                    .setMarketGroupId(m.getMarketGroupId().trim())
                    .setCountryCodes(new ArrayList<>(codes)));
        }
        if (cleaned.isEmpty()) {
            throw new CustomException("markets must include at least one country");
        }
        return JSON.toJSONString(cleaned);
    }

    private static List<MarketSelectionDTO> decodeMarkets(String json) {
        try {
            List<MarketSelectionDTO> list = JSON.parseArray(json, MarketSelectionDTO.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static <E extends Enum<E>> String normalizeEnum(String raw, Class<E> type, String fallback) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (Exception e) {
            throw new CustomException("invalid " + type.getSimpleName() + ": " + raw);
        }
    }
}
