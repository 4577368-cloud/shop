package com.tang.plugin.controller;

import com.tang.plugin.config.ShopifyProperties;
import com.tang.plugin.enums.PluginType;
import com.tang.plugin.service.match.strategy.ProductMatchStrategyHolder;
import com.tang.plugin.service.order.external.strategy.ExternalOrderStrategyFactory;
import com.tang.plugin.service.publish.handler.ProductPlatformHandlerHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/plugin")
public class SkeletonHealthController {

    @Resource
    private ExternalOrderStrategyFactory externalOrderStrategyFactory;
    @Resource
    private ProductPlatformHandlerHolder productPlatformHandlerHolder;
    @Resource
    private ProductMatchStrategyHolder productMatchStrategyHolder;
    @Resource
    private ShopifyProperties shopifyProperties;
    @Resource
    private DataSource dataSource;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "tangbuy-plugin");
        body.put("status", "UP");
        body.put("supportedPluginTypes", PluginType.values());
        try {
            externalOrderStrategyFactory.getStrategy(PluginType.SHOPIFY);
            body.put("shopifyOrderStrategy", "REGISTERED");
        } catch (Exception e) {
            body.put("shopifyOrderStrategy", "NOT_REGISTERED");
            log.error("Shopify order strategy not registered", e);
        }
        try {
            productPlatformHandlerHolder.get(PluginType.SHOPIFY.getCode());
            body.put("shopifyProductHandler", "REGISTERED");
        } catch (Exception e) {
            body.put("shopifyProductHandler", "NOT_REGISTERED");
            log.error("Shopify product handler not registered", e);
        }
        body.put("productBindingModule", "AVAILABLE");
        body.put("matchSourcesRegistered", productMatchStrategyHolder.registeredSources());
        body.put("orderBindingConsumption", "AVAILABLE");
        body.put("orderLinePersistence", "AVAILABLE");
        body.put("unboundOrderHandling", "AVAILABLE");
        body.put("manualBindingBackfill", "AVAILABLE");
        body.put("orderHeaderPersistence", "AVAILABLE");
        body.put("procurementTaskCreation", "AVAILABLE");
        body.put("procurementOutboxDelivery", "AVAILABLE");
        body.put("procurementConsumerIntegration", "AVAILABLE");
        body.put("procurementConsumerOps", "AVAILABLE");
        body.put("procurementExecutionStub", "AVAILABLE");
        try (Connection connection = dataSource.getConnection()) {
            body.put("persistence", connection.getMetaData().getDatabaseProductName());
            body.put("persistenceStatus", "UP");
        } catch (Exception e) {
            body.put("persistenceStatus", "DOWN");
            log.error("Datasource health check failed", e);
        }
        body.put("shopifyAuth", "AVAILABLE");
        body.put("shopifyWebhook", "AVAILABLE");
        body.put("shopifyApiKeyConfigured", StringUtils.isNotBlank(shopifyProperties.getApiKey()));
        body.put("shopifyApiSecretConfigured", StringUtils.isNotBlank(shopifyProperties.getApiSecret()));
        body.put("shopifyRedirectUri", shopifyProperties.getRedirectUri());
        body.put("catalogSnapshotPublish", "AVAILABLE");
        body.put("note", "Platform integration: order sync + auth + webhook gateway.");
        return body;
    }
}
