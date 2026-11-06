package com.splitsmart;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Release-specific check: production is never contacted. */
class ReleaseMigrationPermissionsIT {
    @Test
    void schemaScopedMigratorUpgradesV7WhileDmlRuntimeCannotChangeSchema() throws Exception {
        String migrationPassword = UUID.randomUUID().toString();
        String runtimePassword = UUID.randomUUID().toString();
        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))) {
            mysql.withDatabaseName("release_readiness").withUsername("fixture_owner")
                    .withPassword(UUID.randomUUID().toString()).withEnv("MYSQL_ROOT_HOST", "%");
            mysql.start();
            var baseline = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target("7").load();
            baseline.migrate();
            var checksums = Arrays.stream(baseline.info().applied()).map(m -> m.getChecksum()).toList();
            try (var root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                    var sql = root.createStatement()) {
                sql.executeUpdate("INSERT INTO users(id,name,email,password_hash) VALUES(1,'Existing','existing@example.invalid','preserved-hash')");
                sql.executeUpdate("INSERT INTO expense_groups(id,name,created_by) VALUES(1,'Existing group',1)");
                sql.executeUpdate("INSERT INTO group_members(group_id,user_id) VALUES(1,1)");
                sql.executeUpdate("INSERT INTO expenses(id,group_id,description,amount,currency_code,paid_by_user_id,created_by_user_id,expense_date,category) VALUES(1,1,'Preserved',123.45,'INR',1,1,'2026-01-01','OTHER')");
                sql.executeUpdate("INSERT INTO expense_splits(expense_id,user_id,amount) VALUES(1,1,123.45)");
                // Random disposable credentials, never printed or used outside this container.
                sql.executeUpdate("CREATE USER 'release_migrator'@'%' IDENTIFIED BY '" + migrationPassword + "'");
                sql.executeUpdate("CREATE USER 'release_runtime'@'%' IDENTIFIED BY '" + runtimePassword + "'");
                sql.executeUpdate("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON release_readiness.* TO 'release_migrator'@'%'");
                sql.executeUpdate("GRANT SELECT,INSERT,UPDATE,DELETE ON release_readiness.* TO 'release_runtime'@'%'");
            }
            var release = Flyway.configure().dataSource(mysql.getJdbcUrl(), "release_migrator", migrationPassword)
                    .target("10").load();
            assertEquals(3, release.migrate().migrationsExecuted);
            release.validate();
            assertEquals(checksums, Arrays.stream(release.info().applied()).limit(7).map(m -> m.getChecksum()).toList());
            assertEquals(10, release.info().applied().length);
            try (var runtime = DriverManager.getConnection(mysql.getJdbcUrl(), "release_runtime", runtimePassword);
                    var sql = runtime.createStatement()) {
                try (var row = sql.executeQuery("SELECT u.password_hash,u.auth_token_version,u.email_verified_at,e.amount,s.amount FROM users u JOIN expenses e ON e.paid_by_user_id=u.id JOIN expense_splits s ON s.expense_id=e.id WHERE u.id=1")) {
                    assertTrue(row.next());
                    assertEquals("preserved-hash", row.getString(1));
                    assertEquals(0, row.getLong(2));
                    assertNull(row.getObject(3));
                    assertEquals("123.45", row.getBigDecimal(4).toPlainString());
                    assertEquals("123.45", row.getBigDecimal(5).toPlainString());
                }
                assertEquals(1142, assertThrows(SQLException.class,
                        () -> sql.executeUpdate("ALTER TABLE users ADD COLUMN forbidden INT")).getErrorCode());
                assertEquals(1142, assertThrows(SQLException.class,
                        () -> sql.executeUpdate("CREATE TABLE forbidden (id BIGINT PRIMARY KEY)")).getErrorCode());
                assertEquals(1, sql.executeUpdate("UPDATE users SET auth_token_version=auth_token_version+1 WHERE id=1"));
                sql.executeUpdate("INSERT INTO password_reset_tokens VALUES(REPEAT('a',64),1,NOW(6),DATE_ADD(NOW(6),INTERVAL 30 MINUTE),NULL)");
                sql.executeUpdate("UPDATE password_reset_tokens SET consumed_at=NOW(6) WHERE user_id=1");
                sql.executeUpdate("INSERT INTO email_verification_tokens VALUES(REPEAT('b',64),1,NOW(6),DATE_ADD(NOW(6),INTERVAL 30 MINUTE),NULL)");
                sql.executeUpdate("INSERT INTO group_invite_links(group_id,token_hash,created_at,expires_at) VALUES(1,REPEAT('c',64),NOW(6),DATE_ADD(NOW(6),INTERVAL 7 DAY))");
                sql.executeUpdate("INSERT INTO notifications(recipient_id,group_id,event_key,type,title,created_at) VALUES(1,1,'release-test','MEMBERSHIP','Membership changed',NOW(6))");
                assertEquals(1, sql.executeUpdate("UPDATE notifications SET read_at=NOW(6) WHERE recipient_id=1"));
                assertEquals(1, sql.executeUpdate("DELETE FROM notifications WHERE recipient_id=1"));
                assertEquals(1, sql.executeUpdate("DELETE FROM password_reset_tokens WHERE user_id=1"));
                assertEquals(1, sql.executeUpdate("DELETE FROM email_verification_tokens WHERE user_id=1"));
                assertEquals(1, sql.executeUpdate("DELETE FROM group_invite_links WHERE group_id=1"));
                try (var grants = sql.executeQuery("SHOW GRANTS")) {
                    while (grants.next()) {
                        String grant = grants.getString(1);
                        assertFalse(grant.contains("ALL PRIVILEGES"));
                        assertFalse(grant.contains("WITH GRANT OPTION"));
                        assertFalse(grant.contains("CREATE") || grant.contains("ALTER"));
                    }
                }
            }
        }
    }
}
