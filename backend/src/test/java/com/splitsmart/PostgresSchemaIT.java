package com.splitsmart;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly selected local-only helper; never uses application/production env. */
class PostgresSchemaIT {
    @Test void migrateIsolatedSchema() {
        String url=System.getenv("PG_MIGRATION_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:25432/ss_pg_[a-z0-9_]+"));
        var flyway=Flyway.configure().dataSource(url,System.getenv("PG_TEST_USER"),System.getenv("PG_TEST_PASSWORD"))
                .locations("classpath:db/postgresql").target(System.getenv("PG_MIGRATION_TARGET"))
                .cleanDisabled(true).load();
        flyway.migrate(); flyway.validate();
        assertEquals(System.getenv("PG_MIGRATION_TARGET"),flyway.info().current().getVersion().toString());
    }
}
