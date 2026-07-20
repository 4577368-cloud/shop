package com.tang.plugin.service.logistics;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
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

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One logistics strategy template per shop. Defaults are in-memory until the merchant saves.
 * Lane matching consumes this template in Phase 2.
 */
@Slf4j
@Service
public class LogisticsTemplateService {

    private static final String DEFAULT_PACKAGING = PackagingType.MINIMAL.name();
    private static final String DEFAULT_SPEED = SpeedPreference.BALANCED.name();
    /** Default market: North America / United States — honest starter, not a fake “history match”. */
    private static final String DEFAULT_MARKETS_JSON =
            "[{\"marketGroupId\":\"north_america\",\"countryCodes\":[\"US\"]}]";

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
        String speed = normalizeEnum(request.getSpeedPreference(), SpeedPreference.class, DEFAULT_SPEED);
        String marketsJson = encodeMarkets(request.getMarkets());

        LogisticsTemplate saved = logisticsTemplateRepository.upsert(new LogisticsTemplate()
                .setShopName(request.getShopName().trim())
                .setPackaging(packaging)
                .setSpeedPreference(speed)
                .setMarketsJson(marketsJson));
        log.info("Logistics template upserted shopName={} packaging={} speed={} markets={}",
                saved.getShopName(), packaging, speed, marketsJson);
        return toVo(saved, false);
    }

    private LogisticsTemplateVO systemDefault(String shopName) {
        return new LogisticsTemplateVO()
                .setShopName(shopName)
                .setPackaging(DEFAULT_PACKAGING)
                .setSpeedPreference(DEFAULT_SPEED)
                .setMarkets(decodeMarkets(DEFAULT_MARKETS_JSON))
                .setDefaultTemplate(true)
                .setUpdatedAt(null);
    }

    private LogisticsTemplateVO toVo(LogisticsTemplate row, boolean isDefault) {
        return new LogisticsTemplateVO()
                .setShopName(row.getShopName())
                .setPackaging(row.getPackaging())
                .setSpeedPreference(row.getSpeedPreference())
                .setMarkets(decodeMarkets(row.getMarketsJson()))
                .setDefaultTemplate(isDefault)
                .setUpdatedAt(row.getUpdatedAt() == null ? null
                        : DateTimeFormatter.ISO_INSTANT.format(row.getUpdatedAt().atOffset(ZoneOffset.UTC)));
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
