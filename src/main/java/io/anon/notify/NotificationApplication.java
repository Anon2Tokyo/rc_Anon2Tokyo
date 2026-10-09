package io.anon.notify;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(NotificationSettings.class)
public class NotificationApplication {
    public static void main(String[] args) { SpringApplication.run(NotificationApplication.class, args); }

    @Bean
    ApplicationRunner startDelivery(DeliveryDispatcher dispatcher, RocketIngress ingress) {
        return args -> {
            dispatcher.start(); // 先完成数据库恢复，再启动 MQ 接收。
            ingress.start();
        };
    }
}
