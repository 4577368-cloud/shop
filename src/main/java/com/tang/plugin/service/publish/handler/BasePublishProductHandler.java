package com.tang.plugin.service.publish.handler;

import com.tang.plugin.domain.bo.product.PublicProductBO;
import com.tang.plugin.domain.dto.product.PublishProductResultDTO;
import com.tang.plugin.domain.dto.product.SyncThirdPartyPlatformProductDTO;
import com.tang.plugin.domain.dto.product.SyncThirdProductDTO;
import com.tang.plugin.domain.dto.webhook.ProductWebHookDTO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Template method base for product publish / sync.
 * Subclasses only override abstract interaction methods — do not override non-abstract flow.
 */
@Slf4j
public abstract class BasePublishProductHandler {

    protected String channelCode;

    @Resource
    private ProductPlatformHandlerHolder productPlatformHandlerHolder;

    @PostConstruct
    public void register() {
        productPlatformHandlerHolder.register(channelCode, this);
        log.info("Registered PublishProductHandler channelCode={}", channelCode);
    }

    /**
     * Non-abstract orchestration entry — do not override in platform handlers.
     */
    public final SyncThirdPartyPlatformProductDTO syncProducts(SyncThirdProductDTO changeDTO) {
        log.info("syncProducts start shopName={} channel={}", changeDTO.getShopName(), channelCode);
        return getThirdPartyPlatformProductList(changeDTO);
    }

    /**
     * Non-abstract publish entry — do not override in platform handlers.
     */
    public final void publish(PublicProductBO productBO, PublishProductResultDTO resultDTO) {
        log.info("publish start shopName={} channel={}", productBO.getShopName(), channelCode);
        publishProduct(productBO, resultDTO);
    }

    protected abstract void publishProduct(PublicProductBO productBO, PublishProductResultDTO publishProductResultDTO);

    protected abstract SyncThirdPartyPlatformProductDTO getThirdPartyPlatformProductList(SyncThirdProductDTO changeDTO);

    public void handleWebhook(ProductWebHookDTO dto) {
        log.info("handleWebhook default no-op shopName={} event={}", dto.getShopName(), dto.getEvent());
    }

    protected String getShopCurrency(String shopName) {
        return "USD";
    }

    protected void handlerPublishResult(
            Object request, PublishProductResultDTO resultDTO, String externalJson) {
        resultDTO.setSuccess(true);
        resultDTO.setMessage(externalJson);
    }

    protected void handlerPublishFailResult(
            Object request, PublishProductResultDTO resultDTO, String message, int code) {
        resultDTO.setSuccess(false);
        resultDTO.setMessage(code + ":" + message);
    }
}
