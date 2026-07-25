package com.tang.plugin.controller.logistics;

import com.tang.plugin.domain.dto.logistics.LogisticsAcceptanceVO;
import com.tang.plugin.domain.dto.logistics.PatchQuotesRequest;
import com.tang.plugin.domain.dto.logistics.PatchQuotesResult;
import com.tang.plugin.domain.dto.logistics.UpsertAcceptancesRequest;
import com.tang.plugin.domain.dto.logistics.UpsertAcceptancesResult;
import com.tang.plugin.service.logistics.LogisticsAcceptanceService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 物流接受决策 REST API。
 *
 * <p>承接原 Next.js 本地文件存储（accept-decisions-store.ts）的读写职责。
 * 前端 {@code /api/logistics/accept-decision} 与 {@code /api/logistics/patch-quotes}
 * 路由切换为调用本接口，实现多实例部署下的共享存储。
 *
 * <ul>
 *   <li>{@code GET  /api/plugin/logistics/acceptances?shopName=X} 列出某 shop 全量决策</li>
 *   <li>{@code POST /api/plugin/logistics/acceptances} 批量 UPSERT（按 skuId 自然键覆盖）</li>
 *   <li>{@code POST /api/plugin/logistics/acceptances/patch-quotes} 修补已存在决策的线路</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/plugin/logistics/acceptances")
public class LogisticsAcceptanceController {

    @Resource
    private LogisticsAcceptanceService logisticsAcceptanceService;

    /** 列出某 shop 的全量接受决策。 */
    @GetMapping
    public List<LogisticsAcceptanceVO> list(@RequestParam("shopName") String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return List.of();
        }
        return logisticsAcceptanceService.listByShop(shopName);
    }

    /**
     * 批量 UPSERT 接受决策。以 (shop_name, third_platform_sku_id) 为自然键，
     * 命中则覆盖，未命中则插入。
     */
    @PostMapping
    public UpsertAcceptancesResult upsert(@RequestBody UpsertAcceptancesRequest request) {
        return logisticsAcceptanceService.upsert(request);
    }

    /**
     * 修补已存在决策的线路信息。仅更新命中的记录，不创建新记录。
     */
    @PostMapping("/patch-quotes")
    public PatchQuotesResult patchQuotes(@RequestBody PatchQuotesRequest request) {
        return logisticsAcceptanceService.patchQuotes(request);
    }
}
