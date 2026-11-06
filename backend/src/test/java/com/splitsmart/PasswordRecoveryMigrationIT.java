package com.splitsmart;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

class PasswordRecoveryMigrationIT {
    @Test
    void upgradeFromV7PreservesChecksumsAndExistingRecords() throws Exception {
        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))) {
            mysql.withDatabaseName("recovery_migration").withUsername("recovery_migration")
                    .withPassword(UUID.randomUUID().toString());
            mysql.start();
            var before = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .target("7").load();
            before.migrate();
            var checksums = java.util.Arrays.stream(before.info().applied()).map(migration -> migration.getChecksum()).toList();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO users(id,name,email,password_hash) VALUES(1,'Existing','existing@example.invalid','unchanged-hash')");
                sql.executeUpdate("INSERT INTO expense_groups(id,name,created_by,archived) VALUES(1,'Existing group',1,true)");
                sql.executeUpdate("INSERT INTO group_members(group_id,user_id) VALUES(1,1)");
                sql.executeUpdate("INSERT INTO expenses(id,group_id,description,amount,currency_code,paid_by_user_id,created_by_user_id,expense_date,category) VALUES(1,1,'Existing expense',123.45,'INR',1,1,'2026-01-01','OTHER')");
                sql.executeUpdate("INSERT INTO expense_splits(expense_id,user_id,amount) VALUES(1,1,123.45)");
                var after = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).target("8").load();
                after.migrate();
                after.validate();
                assertEquals(8, after.info().applied().length);
                assertEquals(checksums, java.util.Arrays.stream(after.info().applied()).limit(7).map(migration -> migration.getChecksum()).toList());
                try (var rows = sql.executeQuery("SELECT password_hash,auth_token_version,password_reset_requested_at FROM users WHERE id=1")) {
                    assertTrue(rows.next());
                    assertEquals("unchanged-hash", rows.getString(1));
                    assertEquals(0, rows.getLong(2));
                    assertNull(rows.getObject(3));
                }
                try (var rows = sql.executeQuery("SELECT e.amount,e.expense_date,e.category,g.archived,s.amount FROM expenses e JOIN expense_groups g ON g.id=e.group_id JOIN expense_splits s ON s.expense_id=e.id JOIN group_members m ON m.group_id=g.id AND m.user_id=s.user_id")) {
                    assertTrue(rows.next());
                    assertEquals("123.45", rows.getBigDecimal(1).toPlainString());
                    assertEquals("2026-01-01", rows.getString(2));
                    assertEquals("OTHER", rows.getString(3));
                    assertTrue(rows.getBoolean(4));
                    assertEquals("123.45", rows.getBigDecimal(5).toPlainString());
                }
            }
        }
    }
}
