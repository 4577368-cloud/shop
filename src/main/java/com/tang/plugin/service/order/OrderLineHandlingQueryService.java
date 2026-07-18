package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.dto.order.UnboundOrderLineQuery;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries over active UNBOUND order lines and their handling status.
 * Null handling_status is interpreted as PENDING; all queries scope to del_flag = 0 and UNBOUND.
 */
@Slf4j
@Service
public class OrderLineHandlingQueryService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;

    public List<ThirdPlatformOrderLine> listUnbound(UnboundOrderLineQuery query) {
        if (query == null || StringUtils.isBlank(query.getShopName())) {
            throw new CustomException("listUnbound requires shopName");
        }
        return thirdPlatformOrderLineRepository.listUnbound(
                query.getShopName(), query.getHandlingStatus(), query.getOuterOrderId());
    }

    /**
     * Per-shop counts of active UNBOUND lines by handling status (PENDING includes null).
     */
    public Map<String, Integer> countUnboundByHandling(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("countUnboundByHandling requires shopName");
        }
        int pending = thirdPlatformOrderLineRepository
                .countUnboundByHandling(shopName, OrderLineHandlingStatus.PENDING);
        int ignored = thirdPlatformOrderLineRepository
                .countUnboundByHandling(shopName, OrderLineHandlingStatus.IGNORED);
        int resolved = thirdPlatformOrderLineRepository
                .countUnboundByHandling(shopName, OrderLineHandlingStatus.RESOLVED);
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("PENDING", pending);
        result.put("IGNORED", ignored);
        result.put("RESOLVED", resolved);
        result.put("TOTAL", pending + ignored + resolved);
        return result;
    }
}
