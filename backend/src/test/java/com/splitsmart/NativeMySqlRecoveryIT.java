package com.splitsmart;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@ActiveProfiles(value="mysql-it",inheritProfiles=false)
class NativeMySqlRecoveryIT extends PasswordRecoveryIntegrationTest {
    @DynamicPropertySource static void localDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",NativeMySqlMigrationIT::url);
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->System.getenv("SS_MYSQL_SYNTHETIC_PASSWORD"));
        properties.add("app.security.jwt.secret",()->java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }
}
