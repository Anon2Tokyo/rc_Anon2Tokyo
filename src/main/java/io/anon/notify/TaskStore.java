package io.anon.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TaskStore {
    public record Payload(String contactId, String status) {}
    public record Submission(String requestId, String supplier, String operation, Payload payload) {}
    public record Task(long id, String businessId, String requestId, String supplier, String operation,
                       Payload payload, String status, int attemptCount, int totalAttempts, int replayCount,
                       Instant nextAttemptAt, String lastError) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final NotificationSettings settings;

    public TaskStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
                     ObjectMapper json, NotificationSettings settings) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.json = json;
        this.settings = settings;
    }

    // 方法返回时事务已经提交，消费者此后才可以报告成功。
    public void accept(String businessId, byte[] body) throws Exception {
        if (body.length > 16_384) throw new IllegalArgumentException("消息超过 16 KiB");
        Submission submission = json.readValue(body, Submission.class);
        if (!settings.businesses().containsKey(businessId) || submission == null
                || submission.requestId() == null || !submission.requestId().matches("[a-zA-Z0-9_-]{1,100}")
                || submission.supplier() == null || !settings.suppliers().containsKey(submission.supplier())
                || !"UPDATE_CONTACT_STATUS".equals(submission.operation())
                || submission.payload() == null || submission.payload().contactId() == null
                || submission.payload().contactId().isBlank() || submission.payload().contactId().length() > 100
                || !("ACTIVE".equals(submission.payload().status()) || "INACTIVE".equals(submission.payload().status()))) {
            throw new IllegalArgumentException("消息不符合通知契约");
        }
        Instant now = Instant.now();
        try {
            transaction.executeWithoutResult(tx -> jdbc.update("""
                    INSERT INTO notification_task
                    (business_id,request_id,supplier,operation_name,contact_id,contact_status,status,
                     next_attempt_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'PENDING',?,?,?)
                    """, businessId, submission.requestId(), submission.supplier(), submission.operation(),
                    submission.payload().contactId(), submission.payload().status(), stamp(now), stamp(now), stamp(now)));
        } catch (DuplicateKeyException duplicate) {
            Task existing = find(businessId, submission.requestId()).orElseThrow();
            if (!existing.supplier().equals(submission.supplier()) || !existing.operation().equals(submission.operation())
                    || !existing.payload().equals(submission.payload())) {
                throw new IllegalArgumentException("同一请求标识对应不同内容，原任务未修改");
            }
        }
    }

    public Optional<Task> find(String businessId, String requestId) {
        return jdbc.query("SELECT * FROM notification_task WHERE business_id=? AND request_id=?",
                TaskStore::map, businessId, requestId).stream().findFirst();
    }

    public List<Task> due(String businessId, int limit, Instant now) {
        return jdbc.query("""
                SELECT * FROM notification_task WHERE business_id=?
                AND status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?
                ORDER BY next_attempt_at,id LIMIT ?
                """, TaskStore::map, businessId, stamp(now), limit);
    }

    public Optional<Task> claim(long id, Instant now) {
        return transaction.execute(tx -> {
            int changed = jdbc.update("""
                    UPDATE notification_task SET status='PROCESSING', attempt_count=attempt_count+1,
                    total_attempts=total_attempts+1, updated_at=?
                    WHERE id=? AND status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=? AND attempt_count<?
                    """, stamp(now), id, stamp(now), settings.maxAttempts());
            if (changed == 0) return Optional.empty();
            return jdbc.query("SELECT * FROM notification_task WHERE id=?", TaskStore::map, id).stream().findFirst();
        });
    }

    public void complete(Task task, SupplierGateway.Outcome outcome, Instant now) {
        String status;
        Instant next = now;
        if (outcome.success()) status = "SUCCEEDED";
        else if (outcome.retryable() && task.attemptCount() < settings.maxAttempts()) {
            status = "RETRY_WAIT";
            next = now.plus(settings.retryDelays().get(task.attemptCount() - 1));
        } else status = "FAILED";
        // 保留最近失败信息；成功不会抹掉它。total_attempts 防止旧执行结果覆盖新一轮。
        jdbc.update("""
                UPDATE notification_task SET status=?, next_attempt_at=?, last_error=COALESCE(?,last_error), updated_at=?
                WHERE id=? AND status='PROCESSING' AND total_attempts=?
                """, status, stamp(next), outcome.error(), stamp(now), task.id(), task.totalAttempts());
    }

    public boolean replay(String businessId, String requestId, Instant now) {
        return jdbc.update("""
                UPDATE notification_task SET status='PENDING',attempt_count=0,replay_count=replay_count+1,
                next_attempt_at=?,updated_at=? WHERE business_id=? AND request_id=? AND status='FAILED'
                """, stamp(now), stamp(now), businessId, requestId) == 1;
    }

    // 仅在单实例启动、消费者和扫描尚未启动时运行；不会重置已经 FAILED 的任务。
    public void recover(Instant now) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("""
                    UPDATE notification_task SET status='FAILED',last_error='进程中断，执行结果未知，预算已耗尽',updated_at=?
                    WHERE status='PROCESSING' AND attempt_count>=?
                    """, stamp(now), settings.maxAttempts());
            for (int attempts = 1; attempts < settings.maxAttempts(); attempts++) {
                jdbc.update("""
                        UPDATE notification_task SET status='RETRY_WAIT',next_attempt_at=?,
                        last_error='进程中断，执行结果未知',updated_at=? WHERE status='PROCESSING' AND attempt_count=?
                        """, stamp(now.plus(settings.retryDelays().get(attempts - 1))), stamp(now), attempts);
            }
        });
    }

    private static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }

    private static Task map(ResultSet row, int number) throws SQLException {
        return new Task(row.getLong("id"), row.getString("business_id"), row.getString("request_id"),
                row.getString("supplier"), row.getString("operation_name"),
                new Payload(row.getString("contact_id"), row.getString("contact_status")),
                row.getString("status"), row.getInt("attempt_count"), row.getInt("total_attempts"),
                row.getInt("replay_count"), row.getTimestamp("next_attempt_at").toInstant(), row.getString("last_error"));
    }
}
