package com.tang.plugin.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "tang.plugin.mq.enabled", havingValue = "false", matchIfMissing = true)
public class LocalMqProducer implements MqProducer {
    @Override
    public void send(String topic, String tag, String body) {
        log.info("[LocalMq] topic={} tag={} body={}", topic, tag, body);
    }
}
