package com.tang.plugin.service.publish.handler;

import com.tang.common.core.exception.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ProductPlatformHandlerHolder {

    private final Map<String, BasePublishProductHandler> handlers = new ConcurrentHashMap<>();

    public void register(String channelCode, BasePublishProductHandler handler) {
        handlers.put(channelCode, handler);
        log.info("Product handler holder register: {}", channelCode);
    }

    public BasePublishProductHandler get(String channelCode) {
        BasePublishProductHandler handler = handlers.get(channelCode);
        if (handler == null) {
            throw new CustomException("No PublishProductHandler for channel: " + channelCode);
        }
        return handler;
    }
}
