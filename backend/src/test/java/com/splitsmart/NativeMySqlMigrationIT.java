package com.splitsmart;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMySqlMigrationIT {
    static String url() {
        String url=System.getenv("SS_MYSQL_SYNTHETIC_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:23307/ss_mysql_synthetic_[a-f0-9]+\\?sslMode=REQUIRED")) throw new IllegalStateException("Synthetic MySQL identity required");
        return url;
    }
    @Test void syntheticV7ToV10PreservesHistoryAndFinancialRows() throws Exception {
        String url=url(), password=System.getenv("SS_MYSQL_SYNTHETIC_PASSWORD");
        try(var c=DriverManager.getConnection(url,"root",password);var sql=c.createStatement()) {
            try(var r=sql.executeQuery("SELECT DATABASE(),@@port")){assertTrue(r.next());assertTrue(r.getString(1).startsWith("ss_mysql_synthetic_"));assertEquals(23307,r.getInt(2));}
            var before=Flyway.configure().dataSource(url,"root",password).target("7").load();before.migrate();
            var checksums=java.util.Arrays.stream(before.info().applied()).map(x->x.getChecksum()).toList();
            sql.executeUpdate("INSERT INTO users(id,name,email,password_hash) VALUES(1,'Synthetic','migration@example.invalid','synthetic-hash')");
            sql.executeUpdate("INSERT INTO expense_groups(id,name,created_by,archived) VALUES(1,'Synthetic',1,true)");
            sql.executeUpdate("INSERT INTO group_members(group_id,user_id) VALUES(1,1)");
            sql.executeUpdate("INSERT INTO expenses(id,group_id,description,amount,currency_code,paid_by_user_id,created_by_user_id,expense_date,category) VALUES(1,1,'Synthetic',123.45,'INR',1,1,'2026-01-01','OTHER')");
            sql.executeUpdate("INSERT INTO expense_splits(expense_id,user_id,amount) VALUES(1,1,123.45)");
            var after=Flyway.configure().dataSource(url,"root",password).target("10").load();after.migrate();after.validate();
            assertEquals(10,after.info().applied().length);
            assertEquals(checksums,java.util.Arrays.stream(after.info().applied()).limit(7).map(x->x.getChecksum()).toList());
            try(var r=sql.executeQuery("SELECT u.password_hash,u.auth_token_version,e.amount,s.amount FROM users u JOIN expenses e ON e.paid_by_user_id=u.id JOIN expense_splits s ON s.expense_id=e.id WHERE u.id=1")) {
                assertTrue(r.next());assertEquals("synthetic-hash",r.getString(1));assertEquals(0,r.getLong(2));assertEquals("123.45",r.getBigDecimal(3).toPlainString());assertEquals("123.45",r.getBigDecimal(4).toPlainString());assertFalse(r.next());
            }
            after.migrate();assertEquals(10,after.info().applied().length);
        }
    }
}
