package io.anon.notify;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.file.Files;
import java.nio.file.Path;

/** 示例上游：同步发送并检查结果，不承担真实业务数据库与 MQ 发布的一致性。 */
public class DemoPublisher {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("参数: nameserver topic message.json");
        DefaultMQProducer producer = new DefaultMQProducer("notify-demo-publisher");
        producer.setNamesrvAddr(args[0]);
        producer.setVipChannelEnabled(false);
        producer.setRetryTimesWhenSendFailed(2);
        try {
            producer.start();
            var result = producer.send(new Message(args[1], Files.readAllBytes(Path.of(args[2]))));
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("发送结果未确认: " + result.getSendStatus());
            }
            System.out.println("已确认发送，msgId=" + result.getMsgId());
        } finally { producer.shutdown(); }
    }
}
