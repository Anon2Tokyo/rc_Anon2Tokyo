package io.anon.notify;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/internal")
public class OpsController {
    private final TaskStore store;
    private final DeliveryDispatcher dispatcher;
    private final NotificationSettings settings;

    public OpsController(TaskStore store, DeliveryDispatcher dispatcher, NotificationSettings settings) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status(@RequestHeader(value = "X-Ops-Token", defaultValue = "") String token) {
        authorize(token);
        String failure = dispatcher.fatalError();
        return ResponseEntity.status(failure == null ? 200 : 503)
                .body(Map.of("delivery", failure == null ? "RUNNING" : "STOPPED", "detail", failure == null ? "" : failure));
    }

    @GetMapping("/tasks/{business}/{requestId}")
    public TaskStore.Task get(@RequestHeader(value = "X-Ops-Token", defaultValue = "") String token,
                             @PathVariable String business, @PathVariable String requestId) {
        authorize(token);
        return store.find(business, requestId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/tasks/{business}/{requestId}/retry")
    public TaskStore.Task retry(@RequestHeader(value = "X-Ops-Token", defaultValue = "") String token,
                               @PathVariable String business, @PathVariable String requestId) {
        authorize(token);
        if (store.find(business, requestId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!store.replay(business, requestId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败任务可以重投");
        }
        return store.find(business, requestId).orElseThrow();
    }

    private void authorize(String token) {
        if (!MessageDigest.isEqual(settings.opsToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
