package com.splitsmart;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseMetadataMigrationIT {
    @Test
    void additiveMigrationPreservesGenuineHistoricalDateMoneyAndMembership() throws Exception {
        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))) {
            mysql.withDatabaseName("product_migration_test").withUsername("migration_test")
                    .withPassword(UUID.randomUUID().toString());
            mysql.start();
            String url = mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
            Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword()).target("5").load().migrate();
            try (var connection = DriverManager.getConnection(url, mysql.getUsername(), mysql.getPassword());
                    var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO users(id,name,email,password_hash) VALUES (1,'Historical member','history@example.invalid','test-only')");
                sql.executeUpdate("INSERT INTO expense_groups(id,name,created_by) VALUES (1,'Historical group',1)");
                sql.executeUpdate("INSERT INTO group_members(group_id,user_id) VALUES (1,1)");
                sql.executeUpdate("INSERT INTO expenses(id,group_id,description,amount,currency_code,paid_by_user_id,created_by_user_id,created_at) VALUES (1,1,'Recorded historical purchase',123.45,'INR',1,1,'2020-06-10 23:59:00')");
                sql.executeUpdate("INSERT INTO expense_splits(expense_id,user_id,amount) VALUES (1,1,123.45)");
                var flyway = Flyway.configure().dataSource(url, mysql.getUsername(), mysql.getPassword()).load();
                flyway.migrate();
                flyway.validate();
                assertEquals(9, flyway.info().applied().length);
                try (var rows = sql.executeQuery("SELECT expense_date,category,amount,created_at FROM expenses WHERE id=1")) {
                    assertTrue(rows.next());
                    assertEquals("2020-06-10", rows.getString("expense_date"));
                    assertEquals("OTHER", rows.getString("category"));
                    assertEquals("123.45", rows.getBigDecimal("amount").toPlainString());
                    assertEquals(java.time.Instant.parse("2020-06-10T23:59:00Z"), rows.getTimestamp("created_at").toInstant());
                }
                try (var rows = sql.executeQuery("SELECT archived FROM expense_groups WHERE id=1")) {
                    assertTrue(rows.next());
                    assertFalse(rows.getBoolean(1));
                }
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM expense_splits WHERE expense_id=1")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }
}
