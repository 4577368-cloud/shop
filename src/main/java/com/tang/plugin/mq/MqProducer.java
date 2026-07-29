package com.tang.plugin.mq;

/**
 * Thin producer facade — LocalMqProducer (log) or RocketMqProducer (k8s).
 */
public interface MqProducer {
    void send(String topic, String tag, String body);
    default void send(String topic, String body) {
        send(topic, "*", body);
    }
}
