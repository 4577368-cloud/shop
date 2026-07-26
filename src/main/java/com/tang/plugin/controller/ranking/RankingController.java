package com.tang.plugin.controller.ranking;

import com.tang.plugin.domain.dto.ranking.RankImportRequest;
import com.tang.plugin.domain.dto.ranking.RankProductRowDTO;
import com.tang.plugin.domain.entity.ranking.RankProduct;
import com.tang.plugin.domain.entity.ranking.RankSnapshot;
import com.tang.plugin.repository.RankRepository;
import com.tang.plugin.service.user.ShopAccessGuard;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * TikTok ranking board endpoints (path {@code /api/plugin/ranking}).
 *
 * <ul>
 *   <li>{@code POST /import} — upsert a snapshot (keyed by date_range) and replace its products.</li>
 *   <li>{@code GET /snapshots} — list a shop's snapshots (date windows) for switching.</li>
 *   <li>{@code GET /list} — list products for a snapshot, optional L1 category filter.</li>
 * </ul>
 *
 * Public under {@code /api/plugin/**} (outside the procurement internal-token guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/ranking")
public class RankingController {

    /** Maximum products per import to prevent memory pressure and oversized transactions. */
    private static final int MAX_IMPORT_SIZE = 5_000;

    @Resource
    private RankRepository rankRepository;
    @Resource
    private ShopAccessGuard shopAccessGuard;

    @PostMapping("/import")
    public Map<String, Object> importRanking(HttpServletRequest httpRequest,
                                             @RequestParam String shopName,
                                             @RequestBody RankImportRequest request) {
        shopAccessGuard.assertOwner((Long) httpRequest.getAttribute("userId"), shopName);
        int size = request.getProducts() == null ? 0 : request.getProducts().size();
        if (size > MAX_IMPORT_SIZE) {
            throw new com.tang.common.core.exception.CustomException(
                    "Import size exceeds limit of " + MAX_IMPORT_SIZE, 400, "IMPORT_TOO_LARGE");
        }
        String country = request.getCountry() == null ? "" : request.getCountry();
        Long snapshotId = rankRepository.upsertSnapshot(
                shopName, country, request.getDateRange(), request.getStartDate(), request.getEndDate(), size);
        List<RankProduct> products = (request.getProducts() == null ? List.<RankProduct>of()
                : request.getProducts().stream()
                .map(dto -> toEntity(snapshotId, shopName, country, dto))
                .collect(Collectors.toList()));
        rankRepository.replaceProducts(snapshotId, shopName, country, products);
        log.info("Rank import done: shop={} country={} dateRange={} snapshotId={} products={}",
                shopName, country, request.getDateRange(), snapshotId, products.size());
        return Map.of(
                "snapshotId", snapshotId,
                "imported", products.size(),
                "dateRange", request.getDateRange() == null ? "" : request.getDateRange(),
                "country", country);
    }

    @GetMapping("/snapshots")
    public List<RankSnapshot> snapshots(HttpServletRequest request,
                                        @RequestParam String shopName) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        return rankRepository.listSnapshots(shopName);
    }

    @GetMapping("/list")
    public List<RankProduct> list(HttpServletRequest request,
                                  @RequestParam String shopName,
                                  @RequestParam(required = false) Long snapshotId,
                                  @RequestParam(required = false) String categoryL1) {
        shopAccessGuard.assertOwner((Long) request.getAttribute("userId"), shopName);
        if (snapshotId == null) {
            List<RankSnapshot> snaps = rankRepository.listSnapshots(shopName);
            if (snaps.isEmpty()) {
                return List.of();
            }
            snapshotId = snaps.get(0).getId();
        }
        return rankRepository.listProducts(shopName, snapshotId, categoryL1);
    }

    private RankProduct toEntity(Long snapshotId, String shopName, String country, RankProductRowDTO dto) {
        return new RankProduct()
                .setSnapshotId(snapshotId)
                .setShopName(shopName)
                .setCountry(country)
                .setRankNo(dto.getRankNo())
                .setProductTitle(dto.getProductTitle())
                .setImageUrl(dto.getImageUrl())
                .setCategoryL1(dto.getCategoryL1())
                .setCategoryL2(dto.getCategoryL2())
                .setCategoryL3(dto.getCategoryL3())
                .setCategoryPath(dto.getCategoryPath())
                .setPriceUsd(dto.getPriceUsd())
                .setAvgPriceUsd(dto.getAvgPriceUsd())
                .setListedAt(dto.getListedAt())
                .setRating(dto.getRating())
                .setSalesVolume(dto.getSalesVolume())
                .setCommissionRate(dto.getCommissionRate())
                .setGmvUsd(dto.getGmvUsd())
                .setGmvGrowthRate(dto.getGmvGrowthRate())
                .setLiveGmvUsd(dto.getLiveGmvUsd())
                .setVideoGmvUsd(dto.getVideoGmvUsd())
                .setCardGmvUsd(dto.getCardGmvUsd())
                .setCreatorCount(dto.getCreatorCount())
                .setCreatorOrderRate(dto.getCreatorOrderRate())
                .setTiktokUrl(dto.getTiktokUrl());
    }
}
