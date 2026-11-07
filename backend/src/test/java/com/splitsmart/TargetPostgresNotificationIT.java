package com.splitsmart;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@ActiveProfiles(value="target-postgres-it",inheritProfiles=false)
class TargetPostgresNotificationIT extends NotificationSecurityTest {
    @DynamicPropertySource static void target(DynamicPropertyRegistry properties) { TargetPostgresSupport.configure(properties); }
}
