package com.splitsmart;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class SplitSmartApplicationTests {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void migrationCreatesExpectedSchema() {
		var appliedMigrations = flyway.info().applied();
		assertEquals(5, appliedMigrations.length);
		assertEquals(MigrationState.SUCCESS, appliedMigrations[0].getState());
		assertEquals("1", appliedMigrations[0].getVersion().getVersion());
		assertEquals(MigrationState.SUCCESS, appliedMigrations[1].getState());
		assertEquals("2", appliedMigrations[1].getVersion().getVersion());
		assertEquals(MigrationState.SUCCESS, appliedMigrations[2].getState());
		assertEquals("3", appliedMigrations[2].getVersion().getVersion());

		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables "
						+ "WHERE table_schema = 'public' ORDER BY table_name",
				String.class);

		assertEquals(List.of(
				"expense_creation_operations",
                "expense_groups",
				"expense_splits",
				"expenses",
				"flyway_schema_history",
				"group_invitations",
				"group_members",
				"settlements",
				"users"), tables);
	}

}
