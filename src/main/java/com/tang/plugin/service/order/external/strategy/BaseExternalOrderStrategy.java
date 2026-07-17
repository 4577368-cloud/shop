package com.tang.plugin.service.order.external.strategy;

import com.tang.common.core.exception.CustomException;
import com.tang.plugin.component.RedisManager;
import com.tang.plugin.config.TxManger;
import com.tang.plugin.domain.bo.PluginShopBO;
import com.tang.plugin.domain.entity.order.ExternalOrder;
import com.tang.plugin.service.order.TOrderOuterService;
import com.tang.plugin.utils.OrderBizUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Template base for platform order strategies.
 * Subclasses must NOT send HTTP here — use Component + Adapter.
 */
@Slf4j
public abstract class BaseExternalOrderStrategy<T extends ExternalOrder>
        implements ExternalOrderStrategy<T> {

    private static final Set<String> BLOCKED_FINANCIAL = Set.of(
            "PENDING", "VOIDED", "REFUNDED", "EXPIRED"
    );

    @Resource
    protected RedisManager redisManager;
    @Resource
    protected TxManger txManger;
    @Resource
    protected TOrderOuterService tOrderOuterService;

    protected void upsertDraftOrderFromExternal(T externalOrder, PluginShopBO shopBO) {
        if (externalOrder == null || shopBO == null) {
            return;
        }
        String lockKey = OrderBizUtils.operationLockKey(externalOrder.getOrderId());
        redisManager.lockAround(lockKey, () -> {
            createDraftOrderFromExternal(externalOrder, shopBO);
            return null;
        });
    }

    /**
     * Validates invalid states then creates draft under lock (subclass may override create path).
     */
    @Override
    public Long createDraftOrderFromExternal(T externalOrder, PluginShopBO shopBO) {
        assertCreatable(externalOrder, shopBO);
        String lockKey = getPluginType().name() + "CreateDraftOrder:" + externalOrder.getOrderId();
        return redisManager.lockAround(lockKey, () -> {
            List<Long> existing = tOrderOuterService.listOrderIdsByChannelOuterShopNameAndOuterOrderId(
                    shopBO.getShopType().name(), shopBO.getShopName(), externalOrder.getOrderId());
            if (CollectionUtils.isNotEmpty(existing)) {
                throw new CustomException(getPluginType().name() + " order exists: " + externalOrder.getOrderId());
            }
            return createDraftOrder(externalOrder, shopBO, null);
        });
    }

    protected void assertCreatable(T externalOrder, PluginShopBO shopBO) {
        if (externalOrder == null || StringUtils.isBlank(externalOrder.getOrderId())) {
            throw new CustomException("Invalid external order");
        }
        if (Boolean.TRUE.equals(externalOrder.getVoided())
                || Boolean.TRUE.equals(externalOrder.getFullyRefunded())
                || Boolean.TRUE.equals(externalOrder.getCancelled())) {
            throw new CustomException("Skip invalid order status, orderId=" + externalOrder.getOrderId());
        }
        String financial = StringUtils.defaultString(externalOrder.getFinancialStatus()).toUpperCase(Locale.ROOT);
        if (BLOCKED_FINANCIAL.contains(financial)) {
            throw new CustomException("Skip blocked financial status=" + financial
                    + ", shopName=" + shopBO.getShopName()
                    + ", orderId=" + externalOrder.getOrderId());
        }
    }

    /**
     * Narrow DB write — skeleton stores nothing until DAO wired.
     */
    protected Long createDraftOrder(T externalOrder, PluginShopBO shopBO, Object extra) {
        return txManger.run(() -> {
            log.info("Skeleton createDraftOrder shopName={} orderId={}",
                    shopBO.getShopName(), externalOrder.getOrderId());
            return tOrderOuterService.saveDraftOrderSkeleton(shopBO, externalOrder);
        });
    }
}
