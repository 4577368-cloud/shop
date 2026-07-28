package com.tang.plugin.service.logistics;

import com.alibaba.fastjson2.JSON;
import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.logistics.LogisticsAcceptanceVO;
import com.tang.plugin.domain.dto.logistics.LogisticsLineVO;
import com.tang.plugin.domain.dto.logistics.PatchQuotesRequest;
import com.tang.plugin.domain.dto.logistics.PatchQuotesResult;
import com.tang.plugin.domain.dto.logistics.RemoveAcceptancesResult;
import com.tang.plugin.domain.dto.logistics.UpsertAcceptancesRequest;
import com.tang.plugin.domain.dto.logistics.UpsertAcceptancesResult;
import com.tang.plugin.domain.entity.logistics.LogisticsAcceptDecision;
import com.tang.plugin.repository.LogisticsAcceptDecisionRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 物流接受决策服务层。
 *
 * <p>承接原 Next.js 本地文件存储（accept-decisions-store.ts）的职责：
 * 读取 / UPSERT / Patch 报价。所有操作按 shopName 隔离。
 *
 * <p>JSON 字段（recommended_line_json / alternative_lines_json）由本层
 * 用 fastjson2 序列化/反序列化，Repository 只处理字符串。
 */
@Slf4j
@Service
public class LogisticsAcceptanceService {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    @Resource
    private LogisticsAcceptDecisionRepository repository;

    /** 列出某 shop 的全量接受决策（VO 形态，前端可直接消费）。 */
    public List<LogisticsAcceptanceVO> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("list acceptances requires shopName");
        }
        return repository.listByShop(shopName).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 批量 UPSERT 接受决策。以 (shop_name, third_platform_sku_id) 为自然键，
     * 命中则覆盖，未命中则插入。
     */
    public UpsertAcceptancesResult upsert(UpsertAcceptancesRequest request) {
        if (request == null || StringUtils.isBlank(request.getShopName())) {
            throw new CustomException("upsert acceptances requires shopName");
        }
        String shopName = request.getShopName();
        List<LogisticsAcceptanceVO> incoming = request.getAcceptances();
        if (incoming == null || incoming.isEmpty()) {
            return new UpsertAcceptancesResult()
                    .setUpsertedCount(0)
                    .setAcceptances(listByShop(shopName));
        }

        int upserted = 0;
        for (LogisticsAcceptanceVO vo : incoming) {
            if (StringUtils.isBlank(vo.getThirdPlatformSkuId())) {
                log.warn("skip acceptance without thirdPlatformSkuId: shop={}, itemId={}",
                        shopName, vo.getThirdPlatformItemId());
                continue;
            }
            LogisticsAcceptDecision record = toEntity(shopName, vo);
            repository.upsert(record);
            upserted++;
        }

        return new UpsertAcceptancesResult()
                .setUpsertedCount(upserted)
                .setAcceptances(listByShop(shopName));
    }

    /**
     * 修补已存在决策的线路信息。只能修补**已存在**的记录（按 skuId 匹配），
     * 不存在则跳过。不更新 accepted_at。
     */
    public PatchQuotesResult patchQuotes(PatchQuotesRequest request) {
        if (request == null || StringUtils.isBlank(request.getShopName())) {
            throw new CustomException("patch quotes requires shopName");
        }
        String shopName = request.getShopName();
        Map<String, PatchQuotesRequest.QuotePayload> quotes = request.getQuotes();
        if (quotes == null || quotes.isEmpty()) {
            return new PatchQuotesResult()
                    .setPatchedCount(0)
                    .setAcceptances(listByShop(shopName));
        }

        int patched = 0;
        for (Map.Entry<String, PatchQuotesRequest.QuotePayload> entry : quotes.entrySet()) {
            String skuId = entry.getKey();
            PatchQuotesRequest.QuotePayload payload = entry.getValue();
            if (StringUtils.isBlank(skuId) || payload == null
                    || payload.getRecommendedLine() == null) {
                log.warn("skip patch quote without recommendedLine: shop={}, skuId={}",
                        shopName, skuId);
                continue;
            }
            String recommendedLineJson = JSON.toJSONString(payload.getRecommendedLine());
            String alternativeLinesJson = payload.getAlternativeLines() != null
                    ? JSON.toJSONString(payload.getAlternativeLines())
                    : null;
            String quoteStatus = StringUtils.isBlank(payload.getQuoteStatus())
                    ? "SUCCESS"
                    : payload.getQuoteStatus();
            if (repository.patchQuotes(shopName, skuId, quoteStatus,
                    recommendedLineJson, alternativeLinesJson).isPresent()) {
                patched++;
            }
        }

        return new PatchQuotesResult()
                .setPatchedCount(patched)
                .setAcceptances(listByShop(shopName));
    }

    /**
     * Soft-delete acceptances by SKU id so the merchant can reopen decisions.
     */
    public RemoveAcceptancesResult remove(String shopName, List<String> skuIds) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("remove acceptances requires shopName");
        }
        int removed = 0;
        if (skuIds != null) {
            for (String skuId : skuIds) {
                if (StringUtils.isBlank(skuId)) continue;
                removed += repository.softDelete(shopName, skuId.trim());
            }
        }
        return new RemoveAcceptancesResult()
                .setRemovedCount(removed)
                .setAcceptances(listByShop(shopName));
    }

    // ===== 转换 =====

    private LogisticsAcceptanceVO toVO(LogisticsAcceptDecision entity) {
        LogisticsAcceptanceVO vo = new LogisticsAcceptanceVO()
                .setThirdPlatformSkuId(entity.getThirdPlatformSkuId())
                .setThirdPlatformItemId(entity.getThirdPlatformItemId())
                .setQuoteStatus(entity.getQuoteStatus());
        if (entity.getAcceptedAt() != null) {
            vo.setAcceptedAt(ISO_FORMATTER.format(entity.getAcceptedAt()));
        }
        if (StringUtils.isNotBlank(entity.getRecommendedLineJson())) {
            try {
                vo.setRecommendedLine(
                        JSON.parseObject(entity.getRecommendedLineJson(), LogisticsLineVO.class));
            } catch (Exception e) {
                log.warn("failed to parse recommendedLineJson: id={}, json={}",
                        entity.getId(), entity.getRecommendedLineJson(), e);
            }
        }
        if (StringUtils.isNotBlank(entity.getAlternativeLinesJson())) {
            try {
                List<LogisticsLineVO> lines = JSON.parseArray(
                        entity.getAlternativeLinesJson(), LogisticsLineVO.class);
                vo.setAlternativeLines(lines != null ? lines : new ArrayList<>());
            } catch (Exception e) {
                log.warn("failed to parse alternativeLinesJson: id={}, json={}",
                        entity.getId(), entity.getAlternativeLinesJson(), e);
            }
        }
        return vo;
    }

    private LogisticsAcceptDecision toEntity(String shopName, LogisticsAcceptanceVO vo) {
        Instant acceptedAt = StringUtils.isNotBlank(vo.getAcceptedAt())
                ? Instant.parse(vo.getAcceptedAt())
                : Instant.now();
        return new LogisticsAcceptDecision()
                .setShopName(shopName)
                .setThirdPlatformItemId(vo.getThirdPlatformItemId())
                .setThirdPlatformSkuId(vo.getThirdPlatformSkuId())
                .setQuoteStatus(vo.getQuoteStatus())
                .setRecommendedLineJson(
                        vo.getRecommendedLine() != null
                                ? JSON.toJSONString(vo.getRecommendedLine())
                                : null)
                .setAlternativeLinesJson(
                        vo.getAlternativeLines() != null && !vo.getAlternativeLines().isEmpty()
                                ? JSON.toJSONString(vo.getAlternativeLines())
                                : null)
                .setAcceptedAt(acceptedAt);
    }
}
