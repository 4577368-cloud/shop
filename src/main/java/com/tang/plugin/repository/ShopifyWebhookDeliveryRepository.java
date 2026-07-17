package com.tang.plugin.repository;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Slf4j
@Repository
public class ShopifyWebhookDeliveryRepository {

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * @return true if this delivery id is new and recorded; false if duplicate
     */
    public boolean tryRecord(String webhookId, String shopDomain, String topic) {
        if (StringUtils.isBlank(webhookId)) {
            return true;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO shopify_webhook_delivery (webhook_id, shop_domain, topic, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    webhookId,
                    shopDomain,
                    topic,
                    Timestamp.from(Instant.now()));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Duplicate webhook delivery ignored webhookId={} shopDomain={} topic={}",
                    webhookId, shopDomain, topic);
            return false;
        }
    }
}
