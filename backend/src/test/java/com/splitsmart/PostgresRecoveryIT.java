package com.splitsmart;
import org.springframework.test.context.ActiveProfiles;
@ActiveProfiles(value="postgres-it",inheritProfiles=false)
class PostgresRecoveryIT extends PasswordRecoveryIntegrationTest {}
