package com.tang.plugin.service.procurement;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.domain.entity.procurement.ThirdPlatformProcurementTask;
import com.tang.plugin.enums.procurement.ProcurementTaskStatus;
import com.tang.plugin.repository.ThirdPlatformProcurementTaskRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only queries over procurement tasks (del_flag = 0 only).
 */
@Slf4j
@Service
public class ProcurementTaskQueryService {

    @Resource
    private ThirdPlatformProcurementTaskRepository thirdPlatformProcurementTaskRepository;

    public List<ThirdPlatformProcurementTask> listByOrder(String shopName, String outerOrderId) {
        return thirdPlatformProcurementTaskRepository.listByOrder(shopName, outerOrderId);
    }

    public List<ThirdPlatformProcurementTask> listByStatus(String shopName, ProcurementTaskStatus status) {
        if (StringUtils.isBlank(shopName) || status == null) {
            throw new CustomException("listByStatus requires shopName and status");
        }
        return thirdPlatformProcurementTaskRepository.listByStatus(shopName, status);
    }

    public Optional<ThirdPlatformProcurementTask> findByLine(String shopName, String lineId) {
        return thirdPlatformProcurementTaskRepository.findByLine(shopName, lineId);
    }
}
