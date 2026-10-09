package io.anon.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Component
public class SupplierGateway {
    public record Outcome(boolean success, boolean retryable, String error) {
        static Outcome successResult() { return new Outcome(true, false, null); }
        static Outcome retry(String error) { return new Outcome(false, true, error); }
        static Outcome rejected(String error) { return new Outcome(false, false, error); }
    }

    private final NotificationSettings settings;
    private final ObjectMapper json;
    private final Map<String, HttpClient> clients = new HashMap<>();

    public SupplierGateway(NotificationSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        settings.businesses().keySet().forEach(business -> clients.put(business,
                HttpClient.newBuilder().connectTimeout(settings.httpTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER).build()));
    }

    // 两个具体协议放在同一处，先不引入策略注册框架或通用响应规则引擎。
    public Outcome deliver(TaskStore.Task task) {
        var supplier = settings.suppliers().get(task.supplier());
        boolean isA = "supplier-a".equals(task.supplier());
        try {
            Map<String, Object> body = isA
                    ? Map.of("contactId", task.payload().contactId(), "status", task.payload().status())
                    : Map.of("user_id", task.payload().contactId(), "enabled", "ACTIVE".equals(task.payload().status()));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(supplier.baseUrl().resolve(isA ? "/contacts/status" : "/v1/contact/enable"))
                    .timeout(settings.httpTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Notification-Id", task.businessId() + ":" + task.requestId())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (isA) request.header("X-Api-Key", supplier.token());
            else request.header("Authorization", "Bearer " + supplier.token());
            HttpResponse<String> response = clients.get(task.businessId()).send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 408 || status == 429 || status >= 500) return Outcome.retry("HTTP " + status);
            if (status >= 400) return Outcome.rejected("HTTP " + status);
            if (status < 200 || status >= 300) return Outcome.retry("非预期 HTTP " + status);
            JsonNode result = json.readTree(response.body());
            if (result == null) return Outcome.retry("空响应");
            if (isA) {
                JsonNode code = result.path("code");
                if (code.isIntegralNumber() && code.canConvertToInt()) {
                    if (code.intValue() == 0) return Outcome.successResult();
                    if (code.intValue() == 1001) return Outcome.retry("供应商暂时失败 code=1001");
                    if (code.intValue() == 2001) return Outcome.rejected("供应商拒绝 code=2001");
                }
            } else {
                String code = result.path("result").asText("");
                if ("SUCCESS".equals(code)) return Outcome.successResult();
                if ("TEMPORARY_FAILURE".equals(code)) return Outcome.retry("供应商暂时失败");
                if ("INVALID_ARGUMENT".equals(code)) return Outcome.rejected("供应商参数错误");
            }
            return Outcome.retry("未知业务码");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.retry("调用被中断，结果未知");
        } catch (IOException failure) {
            // 不存储响应全文或异常详情，避免将供应商凭据及业务数据暴露到运维错误字段。
            return Outcome.retry("网络或响应解析异常: " + failure.getClass().getSimpleName());
        }
    }
}
