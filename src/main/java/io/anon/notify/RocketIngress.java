package io.anon.notify;

import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RocketIngress {
    private static final Logger log = LoggerFactory.getLogger(RocketIngress.class);
    private final TaskStore store;
    private final NotificationSettings settings;
    private final List<DefaultMQPushConsumer> consumers = new ArrayList<>();

    public RocketIngress(TaskStore store, NotificationSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    public void start() throws Exception {
        for (var entry : settings.businesses().entrySet()) {
            String business = entry.getKey();
            var config = entry.getValue();
            DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(config.consumerGroup());
            consumers.add(consumer);
            consumer.setNamesrvAddr(settings.nameserver());
            consumer.setInstanceName("notify-" + business);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMin(config.consumerThreads());
            consumer.setConsumeThreadMax(config.consumerThreads());
            consumer.setConsumeMessageBatchMaxSize(1);
            consumer.setPullBatchSize(1);
            consumer.setPullThresholdForQueue(32);
            consumer.setMaxReconsumeTimes(settings.mqMaxReconsumeTimes());
            consumer.subscribe(config.topic(), "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    if (consume(business, message.getBody()) != ConsumeConcurrentlyStatus.CONSUME_SUCCESS) {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
        }
    }

    ConsumeConcurrentlyStatus consume(String business, byte[] body) {
        try {
            store.accept(business, body);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception failure) {
            // 不记录原始 payload；非法消息和持续落库失败最终进入该消费组的 DLQ。
            log.warn("业务 {} 接收失败，交还 MQ 重试: {}", business, failure.getClass().getSimpleName());
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    @PreDestroy
    public void close() { consumers.forEach(DefaultMQPushConsumer::shutdown); }
}
