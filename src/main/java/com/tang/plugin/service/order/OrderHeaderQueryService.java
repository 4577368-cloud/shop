package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrder;
import com.tang.plugin.repository.ThirdPlatformOrderRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only queries over persisted order headers (del_flag = 0 only).
 */
@Slf4j
@Service
public class OrderHeaderQueryService {

    @Resource
    private ThirdPlatformOrderRepository thirdPlatformOrderRepository;

    public Optional<ThirdPlatformOrder> findByOuterOrderId(String shopName, String outerOrderId) {
        if (StringUtils.isAnyBlank(shopName, outerOrderId)) {
            return Optional.empty();
        }
        return thirdPlatformOrderRepository.findByOuterOrderId(shopName, outerOrderId);
    }

    public List<ThirdPlatformOrder> listByShop(String shopName) {
        if (StringUtils.isBlank(shopName)) {
            throw new CustomException("listByShop requires shopName");
        }
        return thirdPlatformOrderRepository.listByShop(shopName);
    }
}
