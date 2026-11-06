package com.splitsmart;

import java.time.Duration;
import java.util.UUID;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Runs the same recovery/locking/JWT scenarios against disposable real MySQL. */
@ActiveProfiles("mysql-it")
class NotificationMySqlIT extends NotificationSecurityTest {
    private static final MySQLContainer MYSQL;
    static {
        MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"));
        MYSQL.withDatabaseName("recovery_it").withUsername("recovery_it")
                .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));
        try { MYSQL.start(); }
        catch (RuntimeException | Error failure) { MYSQL.close(); throw failure; }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("app.security.jwt.secret", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @org.junit.jupiter.api.AfterAll
    static void stopDatabase() { MYSQL.close(); }
}
