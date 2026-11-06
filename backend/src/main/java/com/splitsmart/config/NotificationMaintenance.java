package com.splitsmart.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class NotificationMaintenance {
    private final JdbcTemplate jdbc;
    public NotificationMaintenance(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelay=3600000,initialDelay=600000)
    public void cleanup() {
        // Bounded work on free hosting; notification metadata only, never financial rows.
        var ids=jdbc.queryForList("SELECT id FROM notifications WHERE created_at<? ORDER BY id LIMIT 1000",Long.class,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
        if(!ids.isEmpty()) jdbc.batchUpdate("DELETE FROM notifications WHERE id=?",ids.stream().map(id->new Object[]{id}).toList());
    }
}
