package io.anon.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("notification")
public record NotificationSettings(String nameserver, int mqMaxReconsumeTimes, String opsToken, Duration pollInterval,
                                   Duration httpTimeout, List<Duration> retryDelays,
                                   Map<String, Business> businesses, Map<String, Supplier> suppliers) {
    public record Business(String topic, String consumerGroup, int consumerThreads, int deliveryThreads) {}
    public record Supplier(URI baseUrl, String token) {}

    public NotificationSettings {
        if (mqMaxReconsumeTimes < 0) throw new IllegalArgumentException("MQ 重试次数不能为负数");
        if (opsToken == null || opsToken.length() < 16) {
            throw new IllegalArgumentException("OPS_TOKEN 必须配置且至少 16 个字符");
        }
        if (pollInterval == null || pollInterval.toMillis() < 1 || httpTimeout == null
                || httpTimeout.toMillis() < 1 || retryDelays == null || retryDelays.isEmpty()
                || retryDelays.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException("扫描、HTTP 超时及重试间隔必须为正数");
        }
        if (businesses == null || businesses.isEmpty() || suppliers == null
                || !suppliers.keySet().equals(java.util.Set.of("supplier-a", "supplier-b"))) {
            throw new IllegalArgumentException("需要配置业务来源及两个示例供应商");
        }
        var topics = new HashSet<String>();
        var groups = new HashSet<String>();
        businesses.forEach((id, business) -> {
            if (!id.matches("[a-zA-Z0-9_-]{1,64}") || business.consumerThreads() < 1
                    || business.deliveryThreads() < 1 || business.topic() == null
                    || business.consumerGroup() == null || !topics.add(business.topic())
                    || !groups.add(business.consumerGroup())) {
                throw new IllegalArgumentException("业务来源需要独立 Topic、Consumer Group 及正数并发配置");
            }
        });
        suppliers.values().forEach(s -> {
            if (s.baseUrl() == null || !List.of("http", "https").contains(s.baseUrl().getScheme())
                    || s.baseUrl().getHost() == null || s.token() == null || s.token().isBlank()) {
                throw new IllegalArgumentException("供应商需要有效 HTTP(S) 地址及凭据");
            }
        });
    }

    public int maxAttempts() { return retryDelays.size() + 1; }
}
