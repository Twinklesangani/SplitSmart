package com.splitsmart.config;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresProfileTest {
    private Properties read(String resource) throws Exception {
        var p=new Properties();
        try(var stream=getClass().getResourceAsStream("/"+resource)) {
            assertNotNull(stream);p.load(stream);
        }
        return p;
    }
    @Test void postgresProductionRequiresVerifiedTlsAndIndependentMigrationCredentials() throws Exception {
        var pg=read("application-postgres-prod.properties");
        assertEquals("verify-full",pg.getProperty("spring.datasource.hikari.data-source-properties[sslmode]"));
        assertEquals("verify-full",pg.getProperty("spring.flyway.jdbc-properties[sslmode]"));
        assertEquals("${DB_TLS_CA_FILE}",pg.getProperty("spring.flyway.jdbc-properties[sslrootcert]"));
        assertEquals("${FLYWAY_DB_USERNAME}",pg.getProperty("spring.flyway.user"));
        assertEquals("false",pg.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("classpath:db/postgresql",read("application-postgresql.properties").getProperty("spring.flyway.locations"));
        assertEquals("VERIFY_IDENTITY",read("application-prod.properties").getProperty("spring.flyway.jdbc-properties[sslMode]"));
    }
}
