package io.anon.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryDispatcherTest {
    @Test void slowBusinessDoesNotUseOtherBusinessDeliverySlot() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = Executors.newCachedThreadPool();
        server.setExecutor(httpWorkers);
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/contacts/status", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                if (body.contains("slow")) { slowEntered.countDown(); release.await(5, TimeUnit.SECONDS); }
                byte[] bytes = "{\"code\":0}".getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var settings = TestSupport.settings("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(4));
        var fixture = new TestSupport(settings);
        var dispatcher = new DeliveryDispatcher(fixture.store, new SupplierGateway(settings, new ObjectMapper()), settings);
        try {
            for (int i = 0; i < 10; i++) fixture.store.accept("business-a", TestSupport.message("a" + i, "supplier-a", "slow"));
            dispatcher.start();
            assertThat(slowEntered.await(3, TimeUnit.SECONDS)).isTrue();
            fixture.store.accept("business-b", TestSupport.message("b1", "supplier-a", "fast"));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(fixture.store.find("business-b", "b1").orElseThrow().status()).isEqualTo("SUCCEEDED"));
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM notification_task WHERE business_id='business-a' AND status='PROCESSING'", Integer.class)).isEqualTo(1);
            assertThat(fixture.store.find("business-a", "a0").orElseThrow().attemptCount()).isEqualTo(1);
        } finally {
            release.countDown();
            dispatcher.close();
            server.stop(0);
            httpWorkers.shutdownNow();
        }
    }

    @Test void failedResultPersistenceDoesNotRepeatHttpAndStopsNewDelivery() throws Exception {
        var settings = TestSupport.settings("http://127.0.0.1:1", Duration.ofMillis(100));
        TaskStore store = mock(TaskStore.class);
        SupplierGateway gateway = mock(SupplierGateway.class);
        var task = new TaskStore.Task(1, "business-a", "r1", "supplier-a", "UPDATE_CONTACT_STATUS",
                new TaskStore.Payload("c", "ACTIVE"), "PROCESSING", 1, 1, 0, Instant.now(), null);
        when(store.due(eq("business-a"), anyInt(), any())).thenReturn(java.util.List.of(task));
        when(store.due(eq("business-b"), anyInt(), any())).thenReturn(java.util.List.of());
        when(store.claim(eq(1L), any())).thenReturn(java.util.Optional.of(task));
        when(gateway.deliver(task)).thenReturn(SupplierGateway.Outcome.successResult());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(store).complete(any(), any(), any());
        var dispatcher = new DeliveryDispatcher(store, gateway, settings);
        try {
            dispatcher.start();
            await().atMost(Duration.ofSeconds(3)).until(() -> dispatcher.fatalError() != null);
            verify(gateway, times(1)).deliver(task);
            verify(store, times(3)).complete(any(), any(), any());
        } finally { dispatcher.close(); }
    }
}
