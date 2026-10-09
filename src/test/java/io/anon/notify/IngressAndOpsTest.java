package io.anon.notify;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IngressAndOpsTest {
    @Test void mqSuccessMeansCommittedAndCommitFailureMeansRetry() throws Exception {
        var fixture = new TestSupport();
        var ingress = new RocketIngress(fixture.store, fixture.settings);
        byte[] message = TestSupport.message("received", "supplier-a", "c");
        assertThat(ingress.consume("business-a", message)).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(fixture.store.find("business-a", "received")).isPresent();
        assertThat(ingress.consume("business-a", "broken".getBytes())).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        fixture.jdbc.execute("DROP TABLE notification_task");
        assertThat(ingress.consume("business-a", message)).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test void opsRequiresTokenAndAllowsOnlyFailedTaskReplay() throws Exception {
        var fixture = new TestSupport();
        var dispatcher = mock(DeliveryDispatcher.class);
        var mvc = MockMvcBuilders.standaloneSetup(new OpsController(fixture.store, dispatcher, fixture.settings)).build();
        fixture.store.accept("business-a", TestSupport.message("ops", "supplier-a", "c"));
        String path = "/internal/tasks/business-a/ops";
        String token = fixture.settings.opsToken();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/retry")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Ops-Token", token)).andExpect(status().isOk());
        mvc.perform(post(path + "/retry").header("X-Ops-Token", token)).andExpect(status().isConflict());
        var task = fixture.store.claim(fixture.store.find("business-a", "ops").orElseThrow().id(), Instant.now().plusSeconds(1)).orElseThrow();
        fixture.store.complete(task, SupplierGateway.Outcome.rejected("bad"), Instant.now());
        mvc.perform(post(path + "/retry").header("X-Ops-Token", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayCount").value(1)).andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.totalAttempts").value(1));
        mvc.perform(post(path + "/retry").header("X-Ops-Token", token)).andExpect(status().isConflict());
        mvc.perform(get("/internal/tasks/business-a/missing").header("X-Ops-Token", token)).andExpect(status().isNotFound());
        when(dispatcher.fatalError()).thenReturn("database unavailable");
        mvc.perform(get("/internal/status").header("X-Ops-Token", token)).andExpect(status().isServiceUnavailable());
    }
}
