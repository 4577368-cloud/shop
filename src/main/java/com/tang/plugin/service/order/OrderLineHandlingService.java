package com.tang.plugin.service.order;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.entity.order.ThirdPlatformOrderLine;
import com.tang.plugin.enums.order.OrderLineBindingStatus;
import com.tang.plugin.enums.order.OrderLineHandlingStatus;
import com.tang.plugin.repository.ThirdPlatformOrderLineRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Minimal internal handling flow for UNBOUND order lines. Only mutates handling columns.
 * Legal transitions: PENDING↔IGNORED (one-way to IGNORED), PENDING↔RESOLVED (one-way to RESOLVED),
 * and IGNORED/RESOLVED → PENDING (reopen). Same-status is an idempotent no-op (no handled_at refresh).
 */
@Slf4j
@Service
public class OrderLineHandlingService {

    @Resource
    private ThirdPlatformOrderLineRepository thirdPlatformOrderLineRepository;
    @Resource
    private TxManger txManger;

    public void markIgnored(String shopName, String lineId, String note) {
        applyTransition(shopName, lineId, OrderLineHandlingStatus.IGNORED, note, false);
    }

    public void markResolved(String shopName, String lineId, String note) {
        applyTransition(shopName, lineId, OrderLineHandlingStatus.RESOLVED, note, false);
    }

    /**
     * Reopen to PENDING; preserves the existing handling_note.
     */
    public void reopen(String shopName, String lineId) {
        applyTransition(shopName, lineId, OrderLineHandlingStatus.PENDING, null, true);
    }

    private void applyTransition(String shopName, String lineId, OrderLineHandlingStatus target,
                                 String note, boolean reopen) {
        if (StringUtils.isAnyBlank(shopName, lineId)) {
            throw new CustomException("handling requires shopName and lineId");
        }
        ThirdPlatformOrderLine line = thirdPlatformOrderLineRepository.findByLineId(shopName, lineId)
                .orElseThrow(() -> new CustomException("Order line not found, shopName=" + shopName
                        + ", lineId=" + lineId));
        if (line.getDelFlag() != null && line.getDelFlag() != 0) {
            throw new CustomException("Order line is soft-deleted, handling rejected, lineId=" + lineId);
        }
        if (line.getBindingStatus() != OrderLineBindingStatus.UNBOUND) {
            throw new CustomException("Order line is not UNBOUND, handling rejected, lineId=" + lineId
                    + ", bindingStatus=" + line.getBindingStatus());
        }

        OrderLineHandlingStatus current = effectiveStatus(line);
        if (current == target) {
            log.info("Handling idempotent no-op shopName={} lineId={} status={}", shopName, lineId, current);
            return;
        }
        if (!isLegalTransition(current, target)) {
            throw new CustomException("Illegal handling transition " + current + " -> " + target
                    + ", lineId=" + lineId);
        }

        String noteToStore = reopen ? line.getHandlingNote() : note;
        int rows = txManger.run(() ->
                thirdPlatformOrderLineRepository.updateHandling(shopName, lineId, target, noteToStore, Instant.now()));
        if (rows == 0) {
            throw new CustomException("Order line no longer active UNBOUND, handling not applied, lineId=" + lineId);
        }
        log.info("Handling transition shopName={} outerOrderId={} lineId={} handlingStatus: {} -> {}",
                shopName, line.getOuterOrderId(), lineId, current, target);
    }

    private OrderLineHandlingStatus effectiveStatus(ThirdPlatformOrderLine line) {
        return line.getHandlingStatus() == null ? OrderLineHandlingStatus.PENDING : line.getHandlingStatus();
    }

    private boolean isLegalTransition(OrderLineHandlingStatus from, OrderLineHandlingStatus to) {
        return switch (from) {
            case PENDING -> to == OrderLineHandlingStatus.IGNORED || to == OrderLineHandlingStatus.RESOLVED;
            case IGNORED, RESOLVED -> to == OrderLineHandlingStatus.PENDING;
        };
    }
}
