package io.anon.notify;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class DeliveryDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatcher.class);
    private final TaskStore store;
    private final SupplierGateway gateway;
    private final NotificationSettings settings;
    private final Map<String, ThreadPoolExecutor> workers = new HashMap<>();
    private final Map<String, Set<Long>> inFlight = new HashMap<>();
    private ScheduledExecutorService scanner;
    private volatile String fatalError;

    public DeliveryDispatcher(TaskStore store, SupplierGateway gateway, NotificationSettings settings) {
        this.store = store;
        this.gateway = gateway;
        this.settings = settings;
    }

    public void start() {
        // 恢复必须先于任何新任务执行，应用部署契约为单实例。
        store.recover(Instant.now());
        scanner = Executors.newScheduledThreadPool(settings.businesses().size());
        settings.businesses().forEach((business, config) -> {
            int size = config.deliveryThreads();
            workers.put(business, new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(), runnable -> new Thread(runnable, "delivery-" + business)));
            inFlight.put(business, ConcurrentHashMap.newKeySet());
        });
        // 先完成所有 map 的初始化，再启动扫描线程。
        settings.businesses().keySet().forEach(business -> scanner.scheduleWithFixedDelay(
                () -> scan(business), 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS));
    }

    private void scan(String business) {
        if (fatalError != null) return;
        ThreadPoolExecutor executor = workers.get(business);
        Set<Long> submitted = inFlight.get(business);
        int free = executor.getMaximumPoolSize() - submitted.size();
        if (free <= 0) return;
        try {
            for (TaskStore.Task task : store.due(business, free, Instant.now())) {
                if (!submitted.add(task.id())) continue;
                try {
                    executor.execute(() -> {
                        try { execute(task.id()); }
                        finally { submitted.remove(task.id()); }
                    });
                } catch (RejectedExecutionException full) {
                    // 此时尚未领取或增加次数，下一轮扫描仍能取到任务。
                    submitted.remove(task.id());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("业务 {} 扫描失败，下轮继续: {}", business, failure.getClass().getSimpleName());
        }
    }

    private void execute(long id) {
        try {
            var claimed = store.claim(id, Instant.now());
            if (claimed.isEmpty()) return;
            TaskStore.Task task = claimed.get();
            SupplierGateway.Outcome result = gateway.deliver(task);
            // 仅重试写入结果，绝不在结果保存失败时直接再发一次 HTTP。
            for (int saveAttempt = 1; ; saveAttempt++) {
                try {
                    store.complete(task, result, Instant.now());
                    return;
                } catch (RuntimeException failure) {
                    if (saveAttempt >= 3) throw failure;
                    Thread.sleep(100);
                }
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            fatalError = "任务 " + id + " 状态不确定，检查数据库/配置后重启恢复";
            log.error("{} ({})", fatalError, failure.getClass().getSimpleName());
            // 停止新增投递，PROCESSING 留在库中，不让数据库故障造成静默漏任务。
        }
    }

    public String fatalError() { return fatalError; }

    @PreDestroy
    public void close() {
        if (scanner != null) scanner.shutdownNow();
        workers.values().forEach(ThreadPoolExecutor::shutdown);
        for (ThreadPoolExecutor executor : workers.values()) {
            try {
                if (!executor.awaitTermination(settings.httpTimeout().toSeconds() + 5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
