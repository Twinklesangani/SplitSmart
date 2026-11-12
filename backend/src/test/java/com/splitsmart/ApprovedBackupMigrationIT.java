package com.splitsmart;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local approval only. Never logs or exercises restored user credentials. */
class ApprovedBackupMigrationIT {
    private static java.util.Set<String> structure(java.sql.Statement statement) throws Exception {
        var result=new java.util.HashSet<String>();
        try(var rows=statement.executeQuery("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()")) {
            while(rows.next()) {var value=new StringBuilder("INDEX");for(int i=1;i<=7;i++)value.append('|').append(rows.getString(i));result.add(value.toString());}
        }
        try(var rows=statement.executeQuery("SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE()")) {
            while(rows.next()) {var value=new StringBuilder("KEY");for(int i=1;i<=6;i++)value.append('|').append(rows.getString(i));result.add(value.toString());}
        }
        return result;
    }
    @Test void migrateApprovedLocalCopyOnly() throws Exception {
        String url=System.getenv("SS_BACKUP_REHEARSAL_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:23307/ss_backup_rehearsal_[a-f0-9]+\\?sslMode=REQUIRED")) throw new IllegalStateException("Approved local copy required");
        String password=System.getenv("SS_BACKUP_REHEARSAL_PASSWORD");
        try(var connection=DriverManager.getConnection(url,"root",password);var statement=connection.createStatement()) {
            try(var rows=statement.executeQuery("SELECT DATABASE(),@@port,@@datadir")) {
                assertTrue(rows.next());assertTrue(rows.getString(1).startsWith("ss_backup_rehearsal_"));assertEquals(23307,rows.getInt(2));
                assertEquals(java.nio.file.Path.of(System.getenv("SS_BACKUP_REHEARSAL_DATADIR")).toRealPath(),java.nio.file.Path.of(rows.getString(3)).toRealPath());
            }
            var flyway=Flyway.configure().dataSource(url,"root",password).target("10").load();
            var originalStructure=structure(statement);
            assertEquals(7,flyway.info().applied().length);
            var original=java.util.Arrays.stream(flyway.info().applied()).map(x->x.getChecksum()).toList();
            // migrate validates applied history while allowing expected V8-V10
            // pending migrations; standalone validate rejects pending versions.
            flyway.migrate();flyway.validate();
            assertEquals(10,flyway.info().applied().length);
            assertEquals(original,java.util.Arrays.stream(flyway.info().applied()).limit(7).map(x->x.getChecksum()).toList());
            assertTrue(structure(statement).containsAll(originalStructure),"Original indexes or key constraints changed");
        }
    }
}
