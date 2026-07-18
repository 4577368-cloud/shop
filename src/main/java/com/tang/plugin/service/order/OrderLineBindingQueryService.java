package com.tang.plugin.service.order;

import com.tang.plugin.domain.dto.order.OrderBindingSummary;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Read-only queries over persisted order lines. All results are scoped to del_flag = 0 rows.
 * Reuses {@link OrderBindingSummary} for per-order counts (no new stat DTO in P1).
 */
@Slf4j
@Service
public class OrderLineBindingQueryService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;

    public List<ThirdPlatformOrderLine> listByOrder(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return Collections.emptyList();
        }
        return thirdPlatformOrderLineRepository.listByOrder(shopName, outerOrderId);
    }

    public List<ThirdPlatformOrderLine> listBound(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return thirdPlatformOrderLineRepository.listByStatus(shopName, OrderLineBindingStatus.BOUND);
    }

    public List<ThirdPlatformOrderLine> listUnbound(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            return Collections.emptyList();
        }
        return thirdPlatformOrderLineRepository.listByStatus(shopName, OrderLineBindingStatus.UNBOUND);
    }

    public OrderBindingSummary countByOrder(String shopName, String outerOrderId) {
        OrderBindingSummary summary = new OrderBindingSummary()
                .setShopName(shopName)
                .setOrderId(outerOrderId);
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return summary;
        }
        int bound = thirdPlatformOrderLineRepository
                .countByOrderAndStatus(shopName, outerOrderId, OrderLineBindingStatus.BOUND);
        int unbound = thirdPlatformOrderLineRepository
                .countByOrderAndStatus(shopName, outerOrderId, OrderLineBindingStatus.UNBOUND);
        return summary.setTotal(bound + unbound).setBound(bound).setUnbound(unbound);
    }
}
