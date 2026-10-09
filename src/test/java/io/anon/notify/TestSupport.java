package io.anon.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TestSupport {
    final NotificationSettings settings;
    final JdbcTemplate jdbc;
    final TaskStore store;

    TestSupport() throws Exception { this(settings("http://127.0.0.1:1", Duration.ofMillis(200))); }

    TestSupport(NotificationSettings settings) throws Exception {
        this.settings = settings;
        String mysql = System.getenv("NOTIFICATION_TEST_DB_URL");
        if (mysql != null && !mysql.matches("jdbc:mysql://[^/]+/notifications_test(\\?.*)?")) {
            throw new IllegalArgumentException("真实测试只允许专用 notifications_test 数据库");
        }
        var datasource = mysql == null
                ? new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "")
                : new DriverManagerDataSource(mysql, System.getenv("NOTIFICATION_TEST_DB_USER"), System.getenv("NOTIFICATION_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(datasource);
        String schema = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        if (mysql == null) schema = schema.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        jdbc.execute(schema);
        jdbc.update("DELETE FROM notification_task");
        store = new TaskStore(jdbc, new DataSourceTransactionManager(datasource), new ObjectMapper(), settings);
    }

    static NotificationSettings settings(String url, Duration timeout) {
        return new NotificationSettings("127.0.0.1:9876", 16, "test-token-at-least-16", Duration.ofMillis(20), timeout,
                List.of(Duration.ofMillis(10), Duration.ofMillis(30), Duration.ofMillis(50), Duration.ofMillis(80)),
                Map.of("business-a", new NotificationSettings.Business("a", "a-group", 1, 1),
                       "business-b", new NotificationSettings.Business("b", "b-group", 1, 1)),
                Map.of("supplier-a", new NotificationSettings.Supplier(URI.create(url), "demo-a"),
                       "supplier-b", new NotificationSettings.Supplier(URI.create(url), "demo-b")));
    }

    static byte[] message(String id, String supplier, String contact) {
        return ("""
                {"requestId":"%s","supplier":"%s","operation":"UPDATE_CONTACT_STATUS",
                "payload":{"contactId":"%s","status":"ACTIVE"}}
                """).formatted(id, supplier, contact).getBytes(StandardCharsets.UTF_8);
    }
}
