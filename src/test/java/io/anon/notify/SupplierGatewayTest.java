package io.anon.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class SupplierGatewayTest {
    HttpServer server;
    ExecutorService executor;
    SupplierGateway gateway;
    int status = 200;
    String response = "{\"code\":0}";
    long delay;
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> credential = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            credential.set(exchange.getRequestHeaders().getFirst(path.get().startsWith("/contacts") ? "X-Api-Key" : "Authorization"));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        gateway = new SupplierGateway(TestSupport.settings("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300)), new ObjectMapper());
    }

    @AfterEach void close() { server.stop(0); executor.shutdownNow(); }

    TaskStore.Task task(String supplier) {
        return new TaskStore.Task(1, "business-a", "r1", supplier, "UPDATE_CONTACT_STATUS", new TaskStore.Payload("c1", "ACTIVE"),
                "PROCESSING", 1, 1, 0, Instant.now(), null);
    }

    @Test void mapsBothSupplierContracts() throws Exception {
        assertThat(gateway.deliver(task("supplier-a")).success()).isTrue();
        assertThat(path.get()).isEqualTo("/contacts/status");
        assertThat(credential.get()).isEqualTo("demo-a");
        assertThat(new ObjectMapper().readTree(body.get()).path("status").asText()).isEqualTo("ACTIVE");
        response = "{\"result\":\"SUCCESS\"}";
        assertThat(gateway.deliver(task("supplier-b")).success()).isTrue();
        assertThat(path.get()).isEqualTo("/v1/contact/enable");
        assertThat(credential.get()).isEqualTo("Bearer demo-b");
        assertThat(new ObjectMapper().readTree(body.get()).path("enabled").asBoolean()).isTrue();
    }

    @ParameterizedTest @CsvSource({"408,true", "429,true", "500,true", "503,true", "400,false", "401,false", "404,false", "302,true"})
    void classifiesHttpFailures(int code, boolean retryable) {
        status = code;
        var result = gateway.deliver(task("supplier-a"));
        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isEqualTo(retryable);
    }

    @Test void classifiesBusinessFailuresAndDoesNotAcceptUnknownResponse() {
        response = "{\"code\":1001}";
        assertThat(gateway.deliver(task("supplier-a")).retryable()).isTrue();
        response = "{\"code\":2001}";
        assertThat(gateway.deliver(task("supplier-a")).retryable()).isFalse();
        for (String invalid : new String[]{"{}", "null", "not-json", "{\"code\":\"0\"}", "{\"code\":4294967296}"}) {
            response = invalid;
            var result = gateway.deliver(task("supplier-a"));
            assertThat(result.success()).isFalse();
            assertThat(result.retryable()).isTrue();
        }
        response = "{\"result\":\"INVALID_ARGUMENT\"}";
        assertThat(gateway.deliver(task("supplier-b")).retryable()).isFalse();
        response = "{\"result\":\"TEMPORARY_FAILURE\"}";
        assertThat(gateway.deliver(task("supplier-b")).retryable()).isTrue();
    }

    @Test void timeoutIsRetryableAndResultIsUnknown() {
        delay = 800;
        var result = gateway.deliver(task("supplier-a"));
        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
    }
}
