package com.splitsmart;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class TargetPostgresSchemaIT {
    @Test void migrateOnlyApprovedTargetDatabase() throws Exception {
        boolean application="application".equals(System.getenv("PG_TARGET_SCHEMA_KIND"));
        String url=TargetPostgresSupport.verifiedUrl(application);
        assertEquals(url,System.getenv("PG_TARGET_MIGRATION_URL"));
        var flyway=Flyway.configure().dataSource(url,System.getenv("PG_TARGET_MIGRATION_USER"),System.getenv("PG_TARGET_MIGRATION_PASSWORD"))
            .jdbcProperties(Map.of("sslmode","verify-full","sslrootcert",System.getenv("PG_TARGET_CA")))
            .locations("classpath:db/postgresql").schemas("public").defaultSchema("public")
            .cleanDisabled(true).baselineOnMigrate(false).load();
        flyway.migrate();flyway.validate();
        assertEquals("11",flyway.info().current().getVersion().toString());
        assertEquals(11,flyway.info().applied().length);
    }
}
