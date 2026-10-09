package io.anon.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class TaskStoreTest {
    TestSupport fixture;
    TaskStore store;

    @BeforeEach void setup() throws Exception { fixture = new TestSupport(); store = fixture.store; }

    TaskStore.Task create(String id) throws Exception {
        store.accept("business-a", TestSupport.message(id, "supplier-a", "c1"));
        return store.find("business-a", id).orElseThrow();
    }

    @Test void repeatedDeliveryKeepsOneTaskAndConflictsDoNotOverwrite() throws Exception {
        TaskStore.Task task = create("duplicate");
        store.accept("business-a", TestSupport.message("duplicate", "supplier-a", "c1"));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM notification_task", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.accept("business-a", TestSupport.message("duplicate", "supplier-b", "c2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find("business-a", "duplicate").orElseThrow()).isEqualTo(task);
        store.accept("business-b", TestSupport.message("duplicate", "supplier-a", "c1"));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM notification_task", Integer.class)).isEqualTo(2);
    }

    @Test void invalidMessageNeverBecomesTask() {
        assertThatThrownBy(() -> store.accept("business-a", "{}".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.accept("unknown", TestSupport.message("x", "supplier-a", "c"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM notification_task", Integer.class)).isZero();
    }

    @Test void retryBudgetAndDueTimeSurviveUntilFifthFailure() throws Exception {
        TaskStore.Task task = create("retry");
        Instant time = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        for (int attempt = 1; attempt <= 5; attempt++) {
            var claimed = store.claim(task.id(), time).orElseThrow();
            assertThat(claimed.attemptCount()).isEqualTo(attempt);
            assertThat(store.claim(task.id(), time)).isEmpty();
            store.complete(claimed, SupplierGateway.Outcome.retry("temporary"), time);
            task = store.find("business-a", "retry").orElseThrow();
            if (attempt < 5) {
                assertThat(task.status()).isEqualTo("RETRY_WAIT");
                assertThat(task.nextAttemptAt()).isEqualTo(time.plus(fixture.settings.retryDelays().get(attempt - 1)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                assertThat(store.due("business-a", 10, time)).isEmpty();
            } else assertThat(task.status()).isEqualTo("FAILED");
            time = time.plusSeconds(1);
        }
        assertThat(store.due("business-a", 10, time)).isEmpty();
    }

    @Test void rejectionStopsImmediatelyAndSuccessIsTerminal() throws Exception {
        var rejected = store.claim(create("rejected").id(), Instant.now().plusSeconds(1)).orElseThrow();
        store.complete(rejected, SupplierGateway.Outcome.rejected("bad request"), Instant.now());
        assertThat(store.find("business-a", "rejected").orElseThrow().status()).isEqualTo("FAILED");
        var success = store.claim(create("success").id(), Instant.now().plusSeconds(1)).orElseThrow();
        store.complete(success, SupplierGateway.Outcome.successResult(), Instant.now());
        assertThat(store.replay("business-a", "success", Instant.now())).isFalse();
        store.accept("business-a", TestSupport.message("success", "supplier-a", "c1"));
        assertThat(store.find("business-a", "success").orElseThrow().status()).isEqualTo("SUCCEEDED");
    }

    @Test void startupRecoversProcessingWithoutResettingBudgetOrTerminalStates() throws Exception {
        Instant now = Instant.now().plusSeconds(1);
        var processing = store.claim(create("processing").id(), now).orElseThrow();
        var exhausted = store.claim(create("exhausted").id(), now).orElseThrow();
        fixture.jdbc.update("UPDATE notification_task SET attempt_count=5,total_attempts=5 WHERE id=?", exhausted.id());
        var failed = store.claim(create("failed").id(), now).orElseThrow();
        store.complete(failed, SupplierGateway.Outcome.rejected("denied"), now);
        var success = store.claim(create("done").id(), now).orElseThrow();
        store.complete(success, SupplierGateway.Outcome.successResult(), now);
        create("pending");
        var waiting = store.claim(create("waiting").id(), now).orElseThrow();
        store.complete(waiting, SupplierGateway.Outcome.retry("busy"), now.plusSeconds(100));
        Instant scheduled = store.find("business-a", "waiting").orElseThrow().nextAttemptAt();
        store.recover(now.plusSeconds(2));
        var recovered = store.find("business-a", "processing").orElseThrow();
        assertThat(recovered.status()).isEqualTo("RETRY_WAIT");
        assertThat(recovered.attemptCount()).isEqualTo(processing.attemptCount());
        assertThat(recovered.totalAttempts()).isEqualTo(1);
        assertThat(store.find("business-a", "exhausted").orElseThrow().status()).isEqualTo("FAILED");
        assertThat(store.find("business-a", "failed").orElseThrow().lastError()).isEqualTo("denied");
        assertThat(store.find("business-a", "done").orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(store.find("business-a", "pending").orElseThrow().status()).isEqualTo("PENDING");
        assertThat(store.find("business-a", "waiting").orElseThrow().nextAttemptAt()).isEqualTo(scheduled);
    }

    @Test void concurrentReplayStartsOnlyOneRoundAndPreservesHistory() throws Exception {
        var task = store.claim(create("replay").id(), Instant.now().plusSeconds(1)).orElseThrow();
        store.complete(task, SupplierGateway.Outcome.rejected("denied"), Instant.now());
        var executor = Executors.newFixedThreadPool(8);
        try {
            var actions = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) actions.add(() -> store.replay("business-a", "replay", Instant.now()));
            int successes = 0;
            for (var result : executor.invokeAll(actions)) if (result.get()) successes++;
            assertThat(successes).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        var replayed = store.find("business-a", "replay").orElseThrow();
        assertThat(replayed.replayCount()).isEqualTo(1);
        assertThat(replayed.attemptCount()).isZero();
        assertThat(replayed.totalAttempts()).isEqualTo(1);
        assertThat(replayed.lastError()).isEqualTo("denied");
        // 旧轮次结果不能覆盖人工重投后的任务。
        store.complete(task, SupplierGateway.Outcome.successResult(), Instant.now());
        assertThat(store.find("business-a", "replay").orElseThrow().status()).isEqualTo("PENDING");
    }
}
