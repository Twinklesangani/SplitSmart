package com.splitsmart;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.CreateExpenseSplitRequest;
import com.splitsmart.dto.ExpenseResponse;
import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.dto.MemberBalanceResponse;
import com.splitsmart.entity.ExpenseGroup;
import com.splitsmart.entity.ExpenseSplit;
import com.splitsmart.entity.GroupMember;
import com.splitsmart.entity.User;
import com.splitsmart.repository.ExpenseGroupRepository;
import com.splitsmart.repository.ExpenseRepository;
import com.splitsmart.repository.ExpenseSplitRepository;
import com.splitsmart.repository.GroupMemberRepository;
import com.splitsmart.repository.UserRepository;
import com.splitsmart.service.ExpenseService;
import com.splitsmart.service.SettlementSuggestionService;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@org.springframework.context.annotation.Import(SplitSmartMySqlIT.HistorySqlConfig.class)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.login-target-limit=2")
@ActiveProfiles("mysql-it")
class SplitSmartMySqlIT {

	private static final String DATABASE_NAME = "splitsmart_it";
	private static final String MYSQL_IMAGE = "mysql:8.4.13";
	private static final String TEST_JWT_SECRET = newTestSecret();
	private static final MySQLContainer MYSQL;

	static {
		MYSQL = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE));
		try {
			MYSQL.withDatabaseName(DATABASE_NAME)
					.withUsername("splitsmart_it_user")
					.withPassword(newTestSecret())
					.withStartupTimeout(Duration.ofMinutes(2));
			MYSQL.start();
			assertIsolatedContainerConfiguration();
		} catch (RuntimeException | Error failure) {
			MYSQL.close();
			throw failure;
		}
	}

	@LocalServerPort
	private int port;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Flyway flyway;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ExpenseGroupRepository expenseGroupRepository;

	@Autowired
	private GroupMemberRepository groupMemberRepository;

	@Autowired
	private ExpenseRepository expenseRepository;

	@Autowired
	private ExpenseSplitRepository expenseSplitRepository;

	@Autowired
	private ExpenseService expenseService;

	@Autowired
	private SettlementSuggestionService settlementSuggestionService;

	@Autowired
	private TransactionTemplate transactions;

	@MockitoSpyBean
	private ExpenseSplitRepository expenseSplitRepositorySpy;

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	@DynamicPropertySource
	static void configureIsolatedMySql(DynamicPropertyRegistry registry) {
		assertIsolatedContainerConfiguration();
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
		registry.add("app.security.jwt.secret", () -> TEST_JWT_SECRET);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@AfterAll
	static void stopMySqlContainer() {
		MYSQL.close();
	}

	@BeforeEach
	void requireRunningIsolatedContainer() {
		assertTrue(MYSQL.isRunning(), "The MySQL integration profile must not fall back to another database.");
		assertEquals(DATABASE_NAME, MYSQL.getDatabaseName());
	}

	@Test
	void flywayAndHibernateUseTheIsolatedMySqlSchema() throws Exception {
		assertTrue(MYSQL.isRunning());
		assertNotNull(MYSQL.getContainerId());
        assertEquals(10, flyway.info().applied().length);
		assertEquals(MigrationState.SUCCESS, flyway.info().applied()[0].getState());
		assertEquals("1", flyway.info().applied()[0].getVersion().getVersion());
		assertEquals(MigrationState.SUCCESS, flyway.info().applied()[1].getState());
		assertEquals("2", flyway.info().applied()[1].getVersion().getVersion());
		assertEquals(MigrationState.SUCCESS, flyway.info().applied()[2].getState());
		assertEquals("3", flyway.info().applied()[2].getVersion().getVersion());
		flyway.validate();

		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metadata = connection.getMetaData();
			String jdbcUrl = metadata.getURL();
			assertTrue(jdbcUrl.startsWith("jdbc:mysql://"));
			assertTrue(jdbcUrl.contains(":" + MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT) + "/" + DATABASE_NAME));
			assertFalse(jdbcUrl.contains("splitsmart_dev"));
			assertEquals("MySQL", metadata.getDatabaseProductName());
			assertEquals(DATABASE_NAME, jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
			String mysqlVersion = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
			assertNotNull(mysqlVersion);
			assertTrue(mysqlVersion.startsWith("8.4."));
		}

		assertEquals(21, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.table_constraints "
						+ "WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'",
				Integer.class));
		assertEquals(4, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM information_schema.table_constraints "
						+ "WHERE constraint_schema = DATABASE() AND constraint_type = 'UNIQUE' "
						+ "AND constraint_name IN ('uk_users_email', 'uk_group_members_group_user', "
						+ "'uk_expense_splits_expense_user', 'uk_settlements_group_request')",
				Integer.class));
		assertEquals(19, jdbcTemplate.queryForObject(
				"SELECT numeric_precision FROM information_schema.columns "
						+ "WHERE table_schema = DATABASE() AND table_name = 'expenses' AND column_name = 'amount'",
				Integer.class));
		assertEquals(2, jdbcTemplate.queryForObject(
				"SELECT numeric_scale FROM information_schema.columns "
						+ "WHERE table_schema = DATABASE() AND table_name = 'expenses' AND column_name = 'amount'",
				Integer.class));
	}

	@Test
	void actuatorProbesAreRedactedAndOnlyTheProbeGroupsArePublic() throws Exception {
		for (String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
			HttpResponse<String> response = send("GET", path, null, null);
			assertEquals(200, response.statusCode(), response.body());
			var body = objectMapper.readTree(response.body());
			assertEquals("UP", body.path("status").asText());
			assertEquals(1, body.size(), response.body());
		}
		assertEquals(401, send("GET", "/actuator/health", null, null).statusCode());
		assertEquals(401, send("GET", "/actuator/env", null, null).statusCode());
	}

	@Test
	void equalSplitPersistsExactMinorUnitSharesAndReconciledBalances() throws Exception {
		Fixture fixture = createFixture("equal", 3);
		List<Long> ids = fixture.memberIds();
		Map<String, Object> request = new HashMap<>();
		request.put("description", "Equal integration expense");
		request.put("amount", new BigDecimal("1000.00"));
		request.put("paidByUserId", ids.get(0));
		request.put("currencyCode", "INR");
		request.put("splitType", "EQUAL");
		request.put("participantUserIds", List.of(ids.get(2), ids.get(0), ids.get(1)));

		HttpResponse<String> response = send("POST", expensesPath(fixture.groupId()), token(ids.get(0)), request);
		assertEquals(201, response.statusCode());
		ExpenseResponse expense = read(response, ExpenseResponse.class);
		assertEquals(new BigDecimal("1000.00"), expense.amount());

		List<ExpenseSplit> persisted = expenseSplitRepository.findAllByExpense_Id(expense.id()).stream()
				.sorted((left, right) -> left.getUser().getId().compareTo(right.getUser().getId()))
				.toList();
		assertEquals(ids, persisted.stream().map(split -> split.getUser().getId()).toList());
		assertEquals(List.of(
				new BigDecimal("333.34"),
				new BigDecimal("333.33"),
				new BigDecimal("333.33")),
				persisted.stream().map(ExpenseSplit::getAmount).toList());
		assertEquals(new BigDecimal("1000.00"), persisted.stream()
				.map(ExpenseSplit::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add));

		GroupBalanceResponse balances = read(
				send("GET", balancesPath(fixture.groupId()), token(ids.get(0)), null),
				GroupBalanceResponse.class);
		assertEquals(new BigDecimal("1000.00"), balances.totalExpenseAmount());
		assertEquals(new BigDecimal("0.00"), balances.balances().stream()
				.map(MemberBalanceResponse::netBalance)
				.reduce(BigDecimal.ZERO, BigDecimal::add));
		assertEquals(new BigDecimal("1000.00"), balances.balances().stream()
				.map(MemberBalanceResponse::totalShare)
				.reduce(BigDecimal.ZERO, BigDecimal::add));
	}

	@Test
	void exactSplitPersistsCorrectAmountsAndRejectsAnInvalidTotalWithoutPartialRows() throws Exception {
		Fixture fixture = createFixture("exact", 3);
		List<Long> ids = fixture.memberIds();
		Map<String, Object> valid = exactExpenseRequest(
				"Exact integration expense",
				new BigDecimal("900.00"),
				ids.get(0),
				List.of(
						split(ids.get(0), "400.00"),
						split(ids.get(1), "300.00"),
						split(ids.get(2), "200.00")));
		HttpResponse<String> created = send("POST", expensesPath(fixture.groupId()), token(ids.get(0)), valid);
		assertEquals(201, created.statusCode());
		ExpenseResponse saved = read(created, ExpenseResponse.class);
		assertEquals(List.of(
				new BigDecimal("400.00"),
				new BigDecimal("300.00"),
				new BigDecimal("200.00")),
				saved.splits().stream().map(split -> split.amount()).toList());

		long expensesBefore = expenseRepository.countByGroup_Id(fixture.groupId());
		long splitsBefore = expenseSplitRepository.countByExpense_Group_Id(fixture.groupId());
		Map<String, Object> invalid = exactExpenseRequest(
				"Invalid total",
				new BigDecimal("900.00"),
				ids.get(0),
				List.of(
						split(ids.get(0), "400.00"),
						split(ids.get(1), "300.00"),
						split(ids.get(2), "201.00")));
		HttpResponse<String> rejected = send("POST", expensesPath(fixture.groupId()), token(ids.get(0)), invalid);
		assertEquals(400, rejected.statusCode());
		assertEquals(expensesBefore, expenseRepository.countByGroup_Id(fixture.groupId()));
		assertEquals(splitsBefore, expenseSplitRepository.countByExpense_Group_Id(fixture.groupId()));
	}

	@Test
	void groupPrivacyAndCrossGroupExpenseIdentifiersAreEnforcedByMySqlBackedApi() throws Exception {
		Fixture first = createFixture("privacy-first", 2);
		Fixture second = createFixture("privacy-second", 2);
		HttpResponse<String> created = send(
				"POST",
				expensesPath(first.groupId()),
				token(first.memberIds().get(0)),
				exactExpenseRequest(
						"Private first-group expense",
						new BigDecimal("10.00"),
						first.memberIds().get(0),
						List.of(
								split(first.memberIds().get(0), "5.00"),
								split(first.memberIds().get(1), "5.00"))));
		assertEquals(201, created.statusCode());
		ExpenseResponse expense = read(created, ExpenseResponse.class);

		assertEquals(404, send(
				"GET",
				balancesPath(first.groupId()),
				token(second.memberIds().get(0)),
				null).statusCode());
		assertEquals(404, send(
				"GET",
				expensesPath(second.groupId()) + "/" + expense.id(),
				token(second.memberIds().get(0)),
				null).statusCode());
	}

	@Test
	void invalidExpenseUpdateIsRejectedWithoutChangingThePersistedExpenseOrSplits() throws Exception {
		Fixture fixture = createFixture("invalid-update", 2);
		List<Long> ids = fixture.memberIds();
		ExpenseResponse original = createExactExpense(
				fixture,
				"Original expense",
				"100.00",
				List.of(split(ids.get(0), "40.00"), split(ids.get(1), "60.00")));
		long splitCount = expenseSplitRepository.countByExpense_Id(original.id());

		HttpResponse<String> rejected = send(
				"PUT",
				expensesPath(fixture.groupId()) + "/" + original.id(),
				token(ids.get(0)),
				exactExpenseRequest(
						"Rejected update",
						new BigDecimal("100.00"),
						ids.get(0),
						List.of(split(ids.get(0), "40.00"), split(ids.get(1), "61.00"))));
		assertEquals(400, rejected.statusCode());

		ExpenseResponse unchanged = read(send(
				"GET",
				expensesPath(fixture.groupId()) + "/" + original.id(),
				token(ids.get(0)),
				null), ExpenseResponse.class);
		assertEquals("Original expense", unchanged.description());
		assertEquals(new BigDecimal("100.00"), unchanged.amount());
		assertEquals(splitCount, expenseSplitRepository.countByExpense_Id(original.id()));
		assertEquals(List.of(new BigDecimal("40.00"), new BigDecimal("60.00")),
				unchanged.splits().stream().map(split -> split.amount()).toList());
	}

	@Test
	void mysqlTransactionRollsBackExistingExpenseAndSplitDeletionAfterPersistenceFailure() throws Exception {
		Fixture fixture = createFixture("rollback", 2);
		List<Long> ids = fixture.memberIds();
		ExpenseResponse original = createExactExpense(
				fixture,
				"Before injected persistence failure",
				"100.00",
				List.of(split(ids.get(0), "40.00"), split(ids.get(1), "60.00")));
		CreateExpenseRequest replacement = new CreateExpenseRequest(
				"Replacement that fails after old splits are deleted",
				new BigDecimal("200.00"),
				ids.get(0),
				"INR",
				List.of(
						new CreateExpenseSplitRequest(ids.get(0), new BigDecimal("100.00")),
						new CreateExpenseSplitRequest(ids.get(1), new BigDecimal("100.00"))));
		doThrow(new org.springframework.dao.DataIntegrityViolationException("Injected persistence failure"))
				.when(expenseSplitRepositorySpy)
				.saveAllAndFlush(any());

		assertNotNull(assertThrows(
				org.springframework.dao.DataIntegrityViolationException.class,
				() -> expenseService.updateExpense(
						fixture.groupId(),
						original.id(),
						ids.get(0).toString(),
						replacement)));

		ExpenseResponse persisted = expenseService.getExpense(
				fixture.groupId(),
				original.id(),
				ids.get(0).toString());
		assertEquals("Before injected persistence failure", persisted.description());
		assertEquals(new BigDecimal("100.00"), persisted.amount());
		assertEquals(List.of(new BigDecimal("40.00"), new BigDecimal("60.00")),
				persisted.splits().stream().map(split -> split.amount()).toList());
		assertEquals(2, expenseSplitRepository.countByExpense_Id(original.id()));
		assertEquals(1, expenseRepository.countByGroup_Id(fixture.groupId()));
	}

	@Test
	void competingRepaymentsNeverOverSettleAndLeaveTheExpectedDebt() throws Exception {
		for (int iteration = 0; iteration < 3; iteration++) {
			Fixture fixture = createFixture("competing-" + iteration, 2);
			List<Long> ids = fixture.memberIds();
			createExactExpense(
					fixture,
					"Initial debt",
					"100.00",
					List.of(split(ids.get(1), "100.00")));

			List<HttpResponse<String>> responses = concurrently(List.of(
					settlementRequest(fixture, ids.get(1), ids.get(0), "75.00", UUID.randomUUID().toString()),
					settlementRequest(fixture, ids.get(1), ids.get(0), "75.00", UUID.randomUUID().toString())));
			assertEquals(1, responses.stream().filter(response -> response.statusCode() == 201).count());
			assertEquals(1, responses.stream().filter(response -> response.statusCode() == 400).count());
			assertTrue(responses.stream().noneMatch(response -> response.statusCode() >= 500));
			assertEquals(1, settlementCount(fixture.groupId()));
			assertEquals(new BigDecimal("75.00"), jdbcTemplate.queryForObject(
					"SELECT SUM(amount) FROM settlements WHERE group_id = ?",
					BigDecimal.class,
					fixture.groupId()));

			GroupBalanceResponse balances = read(
					send("GET", balancesPath(fixture.groupId()), token(ids.get(1)), null),
					GroupBalanceResponse.class);
			assertEquals(new BigDecimal("-25.00"), balanceFor(balances, ids.get(1)).netBalance());
			assertEquals(new BigDecimal("25.00"), balanceFor(balances, ids.get(0)).netBalance());
			assertEquals(new BigDecimal("25.00"),
					settlementSuggestionService.getSuggestions(
							fixture.groupId(),
							ids.get(1).toString()).totalSuggestedAmount());
		}
	}

	@Test
	void concurrentDuplicateRequestIdCreatesOnlyOneSettlementAndReturnsConflict() throws Exception {
		Fixture fixture = createFixture("duplicate-id", 2);
		List<Long> ids = fixture.memberIds();
		createExactExpense(
				fixture,
				"Duplicate-request debt",
				"100.00",
				List.of(split(ids.get(1), "100.00")));
		String requestId = UUID.randomUUID().toString();

		List<HttpResponse<String>> responses = concurrently(List.of(
				settlementRequest(fixture, ids.get(1), ids.get(0), "25.00", requestId),
				settlementRequest(fixture, ids.get(1), ids.get(0), "25.00", requestId)));
		assertEquals(1, responses.stream().filter(response -> response.statusCode() == 201).count());
		assertEquals(1, responses.stream().filter(response -> response.statusCode() == 409).count());
		assertTrue(responses.stream().noneMatch(response -> response.statusCode() >= 500));
		assertEquals(1, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ? AND request_id = ?",
				Integer.class,
				fixture.groupId(),
				requestId));
		assertEquals(new BigDecimal("25.00"), jdbcTemplate.queryForObject(
				"SELECT SUM(amount) FROM settlements WHERE group_id = ?",
				BigDecimal.class,
				fixture.groupId()));
	}

	@Test
	void expenseUpdateAndSettlementSerializeAgainstTheSameGroupLock() throws Exception {
		Fixture fixture = createFixture("mutation-race", 2);
		List<Long> ids = fixture.memberIds();
		ExpenseResponse original = createExactExpense(
				fixture,
				"Before concurrent update",
				"100.00",
				List.of(split(ids.get(1), "100.00")));
		Map<String, Object> update = exactExpenseRequest(
				"After concurrent update",
				new BigDecimal("40.00"),
				ids.get(0),
				List.of(split(ids.get(1), "40.00")));

		List<HttpResponse<String>> responses = concurrently(List.of(
				() -> send(
						"PUT",
						expensesPath(fixture.groupId()) + "/" + original.id(),
						token(ids.get(0)),
						update),
				settlementRequest(fixture, ids.get(1), ids.get(0), "75.00", UUID.randomUUID().toString())));
		HttpResponse<String> updateResponse = responses.get(0);
		HttpResponse<String> settlementResponse = responses.get(1);
		assertEquals(200, updateResponse.statusCode());
		assertTrue(settlementResponse.statusCode() == 201 || settlementResponse.statusCode() == 400);
		assertTrue(responses.stream().noneMatch(response -> response.statusCode() >= 500));

		ExpenseResponse persisted = read(send(
				"GET",
				expensesPath(fixture.groupId()) + "/" + original.id(),
				token(ids.get(0)),
				null), ExpenseResponse.class);
		assertEquals("After concurrent update", persisted.description());
		assertEquals(new BigDecimal("40.00"), persisted.amount());
		assertEquals(List.of(new BigDecimal("40.00")),
				persisted.splits().stream().map(split -> split.amount()).toList());
		GroupBalanceResponse balances = read(
				send("GET", balancesPath(fixture.groupId()), token(ids.get(0)), null),
				GroupBalanceResponse.class);
		if (settlementResponse.statusCode() == 201) {
			assertEquals(1, settlementCount(fixture.groupId()));
			assertEquals(new BigDecimal("75.00"), jdbcTemplate.queryForObject(
					"SELECT SUM(amount) FROM settlements WHERE group_id = ?",
					BigDecimal.class,
					fixture.groupId()));
			assertEquals(new BigDecimal("35.00"), balanceFor(balances, ids.get(1)).netBalance());
			assertEquals(new BigDecimal("-35.00"), balanceFor(balances, ids.get(0)).netBalance());
		} else {
			assertEquals(0, settlementCount(fixture.groupId()));
			assertEquals(new BigDecimal("-40.00"), balanceFor(balances, ids.get(1)).netBalance());
			assertEquals(new BigDecimal("40.00"), balanceFor(balances, ids.get(0)).netBalance());
		}
		assertEquals(new BigDecimal("0.00"), balances.balances().stream()
				.map(MemberBalanceResponse::netBalance)
				.reduce(BigDecimal.ZERO, BigDecimal::add));
	}

	@Test
	void fourSimultaneousAuthorizedRepaymentsCompleteWithoutOverSettlement() throws Exception {
		Fixture fixture = createFixture("bounded-contention", 2);
		List<Long> ids = fixture.memberIds();
		createExactExpense(
				fixture,
				"Four-way contention debt",
				"100.00",
				List.of(split(ids.get(1), "100.00")));
		List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			requests.add(settlementRequest(
					fixture,
					ids.get(1),
					ids.get(0),
					"25.00",
					UUID.randomUUID().toString()));
		}

		List<HttpResponse<String>> responses = concurrently(requests);
		assertEquals(4, responses.stream().filter(response -> response.statusCode() == 201).count());
		assertTrue(responses.stream().noneMatch(response -> response.statusCode() >= 500));
		assertEquals(4, settlementCount(fixture.groupId()));
		assertEquals(new BigDecimal("100.00"), jdbcTemplate.queryForObject(
				"SELECT SUM(amount) FROM settlements WHERE group_id = ?",
				BigDecimal.class,
				fixture.groupId()));
		GroupBalanceResponse balances = read(
				send("GET", balancesPath(fixture.groupId()), token(ids.get(0)), null),
				GroupBalanceResponse.class);
		assertEquals(new BigDecimal("0.00"), balanceFor(balances, ids.get(0)).netBalance());
		assertEquals(new BigDecimal("0.00"), balanceFor(balances, ids.get(1)).netBalance());
		assertEquals(new BigDecimal("0.00"),
				settlementSuggestionService.getSuggestions(fixture.groupId(), ids.get(0).toString())
						.totalSuggestedAmount());
	}


    @Test
    void mysqlCollationAliasesCannotBypassAccountTargetLimit() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String email = "resume." + suffix + "@example.test";
        User account = userRepository.saveAndFlush(new User("Alias synthetic", email,
                org.springframework.security.crypto.factory.PasswordEncoderFactories.createDelegatingPasswordEncoder()
                        .encode("CorrectPass123!")));
        String[] aliases = {email, "résumé." + suffix + "@example.test", "resumé." + suffix + "@example.test"};
        for (int index = 0; index < aliases.length; index++) {
            assertEquals(account.getId(), userRepository.findByEmail(aliases[index]).orElseThrow().getId());
            var response = send("POST", "/api/auth/login", null,
                    Map.of("email", aliases[index], "password", "IncorrectPass123!"));
            assertEquals(index < 2 ? 401 : 429, response.statusCode(), response.body());
        }
    }



    @Autowired private HistorySqlCapture historySql;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    static class HistorySqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        volatile boolean recording;
        final java.util.List<String> statements = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override
        public String inspect(String sql) {
            if (recording) statements.add(sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "));
            return sql;
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class HistorySqlConfig {
        @org.springframework.context.annotation.Bean
        HistorySqlCapture historySqlCapture() { return new HistorySqlCapture(); }
        @org.springframework.context.annotation.Bean
        org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer historySqlCustomizer(HistorySqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }
    }

    @Test
    void expenseHistoryIsBoundedAtTheDatabaseAndBalancesUseTheWholeLedger() throws Exception {
        Fixture fixture = createFixture("history-page", 2);
        seedExpenseHistory(fixture, 26);
        jdbcTemplate.update("UPDATE expenses SET created_at = '2026-01-01 00:00:00' WHERE group_id = ?", fixture.groupId());
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM expenses WHERE group_id = ? ORDER BY id DESC",
                Long.class, fixture.groupId());
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        historySql.statements.clear();
        historySql.recording = true;
        HttpResponse<String> response;
        try {
            response = send("GET", expensesPath(fixture.groupId()) + "?limit=5", token(fixture.memberIds().get(0)), null);
        } finally {
            historySql.recording = false;
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
        assertEquals(200, response.statusCode(), response.body());
        var rows = objectMapper.readTree(response.body());
        assertEquals(5, rows.size());
        for (int index = 0; index < 5; index++) {
            assertEquals(ids.get(index).longValue(), rows.get(index).path("id").asLong());
            assertEquals("1.00", rows.get(index).path("amount").asText());
            assertTrue(rows.get(index).path("amount").isTextual());
        }
        long loaded = statistics.getEntityStatistics(com.splitsmart.entity.Expense.class.getName()).getLoadCount();
        assertEquals(5, loaded);
        var historyQueries = historySql.statements.stream().filter(sql -> sql.contains("from expenses ")).toList();
        assertEquals(2, historyQueries.size(), historyQueries.toString());
        assertTrue(historySql.statements.size() <= 6, historySql.statements.toString());
        assertTrue(historyQueries.get(0).contains("limit"), historyQueries.toString());
        assertFalse(historyQueries.get(0).contains("join expense_splits"), historyQueries.toString());
        assertTrue(java.util.regex.Pattern.compile("\\bin\\s*\\(").matcher(historyQueries.get(1)).find(), historyQueries.toString());
        assertFalse(historyQueries.stream().anyMatch(sql -> sql.contains("count(")));
        String cursor = response.headers().firstValue("X-Next-Before").orElseThrow();
        assertEquals(ids.get(4).toString(), cursor);
        seedExpenseHistory(fixture, 1);
        var second = send("GET", expensesPath(fixture.groupId()) + "?limit=5&before=" + cursor,
                token(fixture.memberIds().get(0)), null);
        var secondRows = objectMapper.readTree(second.body());
        for (int index = 0; index < 5; index++) assertEquals(ids.get(index + 5).longValue(), secondRows.get(index).path("id").asLong());
        expenseService.deleteExpense(fixture.groupId(), ids.get(10), fixture.memberIds().get(0).toString());
        var third = send("GET", expensesPath(fixture.groupId()) + "?limit=5&before="
                + second.headers().firstValue("X-Next-Before").orElseThrow(), token(fixture.memberIds().get(0)), null);
        assertEquals(ids.get(11).longValue(), objectMapper.readTree(third.body()).get(0).path("id").asLong());
        var balances = read(send("GET", balancesPath(fixture.groupId()), token(fixture.memberIds().get(0)), null),
                GroupBalanceResponse.class);
        assertEquals(new BigDecimal("26.00"), balances.totalExpenseAmount());
        var plan = jdbcTemplate.queryForMap(
                "EXPLAIN SELECT id FROM expenses WHERE group_id = ? AND (? IS NULL OR id < ?) ORDER BY id DESC LIMIT 6",
                fixture.groupId(), ids.get(4), ids.get(4));
        assertTrue(String.valueOf(plan.get("possible_keys")).contains("idx_expenses_group_id_id"), plan.toString());
        System.out.println("EXPENSE_HISTORY_ENTITY_LOADS=" + loaded + " SQL_COUNT=" + historyQueries.size() + " INCLUDING_ACCESS_CHECKS=" + historySql.statements.size());
        System.out.println("EXPENSE_HISTORY_PLAN=" + plan);
    }

    @Test
    void historyBoundariesAndPrivacyRejectUnboundedOrAmbiguousParameters() throws Exception {
        Fixture fixture = createFixture("history-boundary", 2);
        String auth = token(fixture.memberIds().get(0));
        String[] paths = {expensesPath(fixture.groupId()), "/api/groups/" + fixture.groupId() + "/settlements"};
        for (String path : paths) {
            var empty = send("GET", path, auth, null);
            assertEquals(0, objectMapper.readTree(empty.body()).size());
            assertTrue(empty.headers().firstValue("X-Next-Before").isEmpty());
            for (String query : List.of("limit=0", "limit=-1", "limit=101", "limit=bad", "limit=1.5",
                    "limit=", "before=0", "before=-1", "before=bad", "before=", "offset=0", "offset=-1",
                    "page=1", "sort=id", "limit=1&limit=2")) {
                assertEquals(400, send("GET", path + "?" + query, auth, null).statusCode(), query);
            }
        }
        seedExpenseHistory(fixture, 5);
        var exact = send("GET", paths[0] + "?limit=5", auth, null);
        assertEquals(5, objectMapper.readTree(exact.body()).size());
        assertTrue(exact.headers().firstValue("X-Next-Before").isEmpty());
        var fewer = send("GET", paths[0] + "?limit=100", auth, null);
        assertEquals(5, objectMapper.readTree(fewer.body()).size());
        assertTrue(fewer.headers().firstValue("X-Next-Before").isEmpty());
        Fixture other = createFixture("history-other", 2);
        seedExpenseHistory(other, 1);
        for (String path : paths) {
            var hidden = send("GET", path + "?limit=1", token(other.memberIds().get(0)), null);
            assertEquals(404, hidden.statusCode());
            assertTrue(hidden.headers().firstValue("X-Next-Before").isEmpty());
        }
        Long otherCursor = jdbcTemplate.queryForObject("SELECT MAX(id) FROM expenses WHERE group_id = ?", Long.class, other.groupId());
        assertNotNull(otherCursor, "Expected an expense in the other group");
        var scoped = send("GET", paths[0] + "?before=" + otherCursor, auth, null);
        for (var row : objectMapper.readTree(scoped.body())) assertEquals(fixture.groupId(), row.path("groupId").asLong());
    }

    @Test
    void largeExpenseHistoryHasStrictMaximumWithoutTruncatingBalancesOrSuggestions() throws Exception {
        Fixture fixture = createFixture("history-large", 2);
        seedExpenseHistory(fixture, 120);
        String auth = token(fixture.memberIds().get(0));
        var first = send("GET", expensesPath(fixture.groupId()), auth, null);
        assertEquals(25, objectMapper.readTree(first.body()).size());
        var maximum = send("GET", expensesPath(fixture.groupId()) + "?limit=100", auth, null);
        assertEquals(100, objectMapper.readTree(maximum.body()).size());
        var last = send("GET", expensesPath(fixture.groupId()) + "?limit=100&before="
                + maximum.headers().firstValue("X-Next-Before").orElseThrow(), auth, null);
        assertEquals(20, objectMapper.readTree(last.body()).size());
        assertTrue(last.headers().firstValue("X-Next-Before").isEmpty());
        var balances = read(send("GET", balancesPath(fixture.groupId()), auth, null), GroupBalanceResponse.class);
        assertEquals(new BigDecimal("120.00"), balances.totalExpenseAmount());
        assertEquals(new BigDecimal("120.00"),
                settlementSuggestionService.getSuggestions(fixture.groupId(), fixture.memberIds().get(0).toString()).totalSuggestedAmount());
    }

    @Test
    void settlementPagesKeepFullBalancesAndRecoverAnOlderRequestByItsUniqueKey() throws Exception {
        Fixture fixture = createFixture("settlement-history", 2);
        seedExpenseHistory(fixture, 1);
        var ids = fixture.memberIds();
        String oldestRequest = UUID.randomUUID().toString();
        for (int index = 0; index < 26; index++) {
            var response = settlementRequest(fixture, ids.get(1), ids.get(0), "0.01",
                    index == 0 ? oldestRequest : UUID.randomUUID().toString()).call();
            assertEquals(201, response.statusCode(), response.body());
        }
        jdbcTemplate.update("UPDATE settlements SET created_at = '2026-01-01 00:00:00' WHERE group_id = ?", fixture.groupId());
        String path = "/api/groups/" + fixture.groupId() + "/settlements";
        String auth = token(ids.get(0));
        var first = send("GET", path, auth, null);
        assertEquals(25, objectMapper.readTree(first.body()).size());
        var last = send("GET", path + "?before=" + first.headers().firstValue("X-Next-Before").orElseThrow(), auth, null);
        assertEquals(1, objectMapper.readTree(last.body()).size());
        assertTrue(last.headers().firstValue("X-Next-Before").isEmpty());
        historySql.statements.clear();
        historySql.recording = true;
        HttpResponse<String> found;
        try { found = send("GET", path + "?requestId=" + oldestRequest, auth, null); }
        finally { historySql.recording = false; }
        var matching = objectMapper.readTree(found.body());
        assertEquals(1, matching.size());
        assertEquals(oldestRequest, matching.get(0).path("requestId").asText());
        assertEquals("0.01", matching.get(0).path("amount").asText());
        assertEquals(1, historySql.statements.stream().filter(sql -> sql.contains("from settlements ")).count());
        assertTrue(historySql.statements.stream().anyMatch(sql -> sql.contains("from settlements ") && sql.contains("limit")));
        assertEquals(400, send("GET", path + "?requestId=bad", auth, null).statusCode());
        assertEquals(400, send("GET", path + "?requestId=" + oldestRequest + "&before=1", auth, null).statusCode());
        Fixture other = createFixture("settlement-history-outsider", 2);
        assertEquals(404, send("GET", path + "?requestId=" + oldestRequest, token(other.memberIds().get(0)), null).statusCode());
        var absent = send("GET", "/api/groups/" + other.groupId() + "/settlements?requestId=" + oldestRequest,
                token(other.memberIds().get(0)), null);
        assertEquals(0, objectMapper.readTree(absent.body()).size());
        var balances = read(send("GET", balancesPath(fixture.groupId()), auth, null), GroupBalanceResponse.class);
        assertEquals(new BigDecimal("0.26"), balanceFor(balances, ids.get(1)).totalSentSettlements());
        assertEquals(new BigDecimal("-0.74"), balanceFor(balances, ids.get(1)).netBalance());
        var plan = jdbcTemplate.queryForMap(
                "EXPLAIN SELECT id FROM settlements WHERE group_id = ? AND (? IS NULL OR id < ?) "
                        + "AND (? IS NULL OR request_id = ?) ORDER BY id DESC LIMIT 26",
                fixture.groupId(), null, null, oldestRequest, oldestRequest);
        assertEquals("uk_settlements_group_request", plan.get("key"));
        System.out.println("SETTLEMENT_LOOKUP_PLAN=" + plan);
    }

    private void seedExpenseHistory(Fixture fixture, int count) {
        transactions.executeWithoutResult(status -> {
            var group = expenseGroupRepository.findById(fixture.groupId()).orElseThrow();
            var payer = userRepository.getReferenceById(fixture.memberIds().get(0));
            var borrower = userRepository.getReferenceById(fixture.memberIds().get(1));
            for (int index = 0; index < count; index++) {
                var expense = expenseRepository.saveAndFlush(new com.splitsmart.entity.Expense(
                        group, "History-" + index, new BigDecimal("1.00"), "INR", payer, payer));
                expenseSplitRepository.saveAndFlush(new ExpenseSplit(expense, borrower, new BigDecimal("1.00")));
            }
        });
    }

    @Test
    void invitationSurvivesRegistrationAndOnlyRecipientCanAcceptOnce() throws Exception {
        Fixture group = createFixture("invitation", 1);
        Fixture outsider = createFixture("invitation-outsider", 1);
        String owner = token(group.memberIds().get(0));
        String email = "invite-" + UUID.randomUUID() + "@example.invalid";
        String path = "/api/groups/" + group.groupId() + "/invitations";
        assertEquals(404, send("POST", path, token(outsider.memberIds().get(0)), Map.of("email", email)).statusCode());
        var attempts = concurrently(List.of(
                () -> send("POST", path, owner, Map.of("email", email)),
                () -> send("POST", path, owner, Map.of("email", email))));
        assertEquals(List.of(201, 409), attempts.stream().map(HttpResponse::statusCode).sorted().toList());
        var created = attempts.stream().filter(response -> response.statusCode() == 201).findFirst().orElseThrow();
        assertEquals(201, created.statusCode(), created.body());
        long id = objectMapper.readTree(created.body()).get("id").asLong();
        assertEquals(409, send("POST", path, owner, Map.of("email", email)).statusCode());
        assertEquals(1, groupMemberRepository.findAllWithUserByGroupIdOrderByIdAsc(group.groupId()).size());
        assertEquals(201, send("POST", "/api/auth/register", null,
                Map.of("name", "Invited later", "email", email, "password", newTestSecret())).statusCode());
        long userId = userRepository.findByEmail(email).orElseThrow().getId();
        jdbcTemplate.update("UPDATE users SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?", userId);
        String recipient = token(userId);
        String decision = "/api/invitations/" + id + "/accept";
        assertEquals(404, send("POST", decision, owner, null).statusCode());
        assertEquals(404, send("GET", "/api/groups/" + group.groupId() + "/members", recipient, null).statusCode());
        assertTrue(send("GET", "/api/invitations", recipient, null).body().contains("\"id\":" + id));
        var accepted = concurrently(List.of(
                () -> send("POST", decision, recipient, null),
                () -> send("POST", decision, recipient, null)));
        assertEquals(List.of(204, 409), accepted.stream().map(HttpResponse::statusCode).sorted().toList());
        assertTrue(groupMemberRepository.existsByGroup_IdAndUser_Id(group.groupId(), userId));
        assertEquals(200, send("GET", balancesPath(group.groupId()), recipient, null).statusCode());
        assertEquals("[]", send("GET", "/api/invitations", recipient, null).body());
    }

    @Test
    void declinedAndExpiredInvitationsCannotCreateMembershipOrBeReused() throws Exception {
        Fixture group = createFixture("invite-expiry", 1);
        Fixture recipientGroup = createFixture("invite-recipient", 1);
        long recipientId = recipientGroup.memberIds().get(0);
        String email = userRepository.findById(recipientId).orElseThrow().getEmail();
        String owner = token(group.memberIds().get(0));
        jdbcTemplate.update("UPDATE users SET email_verified_at=CURRENT_TIMESTAMP WHERE id=?", recipientId);
        String recipient = token(recipientId);
        String path = "/api/groups/" + group.groupId() + "/invitations";
        long first = objectMapper.readTree(send("POST", path, owner, Map.of("email", email)).body()).get("id").asLong();
        assertEquals(204, send("POST", "/api/invitations/" + first + "/decline", recipient, null).statusCode());
        assertFalse(groupMemberRepository.existsByGroup_IdAndUser_Id(group.groupId(), recipientId));
        assertEquals(409, send("POST", "/api/invitations/" + first + "/accept", recipient, null).statusCode());
        long second = objectMapper.readTree(send("POST", path, owner, Map.of("email", email)).body()).get("id").asLong();
        assertTrue(second != first);
        jdbcTemplate.update("UPDATE group_invitations SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 DAY WHERE id = ?", second);
        assertEquals(409, send("POST", "/api/invitations/" + second + "/accept", recipient, null).statusCode());
        assertFalse(groupMemberRepository.existsByGroup_IdAndUser_Id(group.groupId(), recipientId));
        long third = objectMapper.readTree(send("POST", path, owner, Map.of("email", email)).body()).get("id").asLong();
        assertTrue(third != second);
        assertEquals(409, send("POST", "/api/invitations/" + second + "/accept", recipient, null).statusCode());
        assertEquals(204, send("POST", "/api/invitations/" + third + "/accept", recipient, null).statusCode());
    }

    @Test
    void dashboardUsesExactScopedTotalsAndExpenseMetadataSurvivesUpdatesAndRetries() throws Exception {
        Fixture first = createFixture("product-dashboard", 2);
        Fixture second = createFixture("product-dashboard-second", 1);
        long ownerId = first.memberIds().get(0);
        String owner = token(ownerId);
        transactions.executeWithoutResult(status -> groupMemberRepository.saveAndFlush(new GroupMember(
                expenseGroupRepository.getReferenceById(second.groupId()), userRepository.getReferenceById(ownerId))));
        var payload = new HashMap<String, Object>();
        payload.put("description", "Dated meal");
        payload.put("amount", "10.00");
        payload.put("paidByUserId", ownerId);
        payload.put("splitType", "EQUAL");
        payload.put("participantUserIds", first.memberIds());
        payload.put("expenseDate", "2024-06-10");
        payload.put("category", "FOOD_DRINKS");
        String operation = UUID.randomUUID().toString();
        var created = sendExpense(first.groupId(), owner, payload, operation);
        assertEquals(201, created.statusCode(), created.body());
        var expense = objectMapper.readTree(created.body());
        assertEquals("2024-06-10", expense.get("expenseDate").asText());
        assertEquals("FOOD_DRINKS", expense.get("category").asText());
        assertEquals(created.body(), sendExpense(first.groupId(), owner, payload, operation).body());
        payload.put("expenseDate", "2024-06-11");
        assertEquals(409, sendExpense(first.groupId(), owner, payload, operation).statusCode());
        payload.put("expenseDate", "2999-01-01");
        assertEquals(400, sendExpense(first.groupId(), owner, payload, UUID.randomUUID().toString()).statusCode());
        payload.put("expenseDate", "2024-06-10");
        payload.put("category", "INVALID");
        assertEquals(400, sendExpense(first.groupId(), owner, payload, UUID.randomUUID().toString()).statusCode());
        payload.remove("category");
        payload.remove("expenseDate");
        var updated = send("PUT", expensesPath(first.groupId()) + "/" + expense.get("id").asLong(), owner, payload);
        assertEquals(200, updated.statusCode());
        assertEquals("FOOD_DRINKS", objectMapper.readTree(updated.body()).get("category").asText());
        assertEquals("2024-06-10", objectMapper.readTree(updated.body()).get("expenseDate").asText());
        assertEquals(201, sendExpense(second.groupId(), token(second.memberIds().get(0)), Map.of(
                "description", "Travel", "amount", "20.00", "paidByUserId", second.memberIds().get(0),
                "splits", List.of(Map.of("userId", ownerId, "amount", "20.00"))), UUID.randomUUID().toString()).statusCode());
        var dashboard = objectMapper.readTree(send("GET", "/api/dashboard", owner, null).body());
        assertEquals("30.00", dashboard.get("totalExpenses").asText());
        assertEquals("20.00", dashboard.get("owed").asText());
        assertEquals("5.00", dashboard.get("receivable").asText());
        assertEquals("-15.00", dashboard.get("netPosition").asText());
        assertEquals(2,dashboard.get("groupBalances").size());
        var positions=new java.util.HashSet<String>();
        dashboard.get("groupBalances").forEach(position->positions.add(position.get("netBalance").asText()));
        assertEquals(java.util.Set.of("5.00","-20.00"),positions);
        assertEquals(2, dashboard.get("groups").size());
        assertEquals(2, dashboard.get("activity").size());
        Instant recorded=Instant.parse(dashboard.get("activity").get(0).get("recordedAt").asText());
        assertTrue(java.time.Duration.between(recorded,Instant.now()).abs().getSeconds()<60,"Dashboard activity must represent the actual recent instant, regardless of JVM timezone.");
        assertEquals(201, send("POST", settlementsPath(first.groupId()), token(first.memberIds().get(1)), Map.of(
                "fromUserId", first.memberIds().get(1), "toUserId", ownerId, "amount", "2.00", "requestId", UUID.randomUUID().toString())).statusCode());
        var after = objectMapper.readTree(send("GET", "/api/dashboard", owner, null).body());
        assertEquals("3.00", after.get("receivable").asText());
        assertEquals("-17.00",after.get("netPosition").asText());
        assertEquals(3, after.get("activity").size());
        assertEquals(401, send("GET", "/api/dashboard", null, null).statusCode());
        Fixture outsider = createFixture("dashboard-private", 1);
        var privateDashboard = objectMapper.readTree(send("GET", "/api/dashboard", token(outsider.memberIds().get(0)), null).body());
        assertEquals("0.00", privateDashboard.get("totalExpenses").asText());
        assertEquals(0, privateDashboard.get("activity").size());
        assertEquals("0.00",privateDashboard.get("netPosition").asText());
        assertEquals(1,privateDashboard.get("groupBalances").size());
        assertEquals(outsider.groupId(),privateDashboard.get("groupBalances").get(0).get("groupId").asLong());
        assertEquals("0.00",privateDashboard.get("groupBalances").get(0).get("netBalance").asText());
    }

    @Test
    void groupManagementProtectsHistoryPermissionsAndArchivedGroups() throws Exception {
        Fixture group = createFixture("managed-group", 3);
        Fixture outsider = createFixture("managed-outsider", 1);
        long ownerId = group.memberIds().get(0);
        long debtorId = group.memberIds().get(1);
        long freshId = group.memberIds().get(2);
        String owner = token(ownerId);
        String base = "/api/groups/" + group.groupId();
        var payload = Map.of("description", "Preserved expense", "amount", "10.00", "paidByUserId", ownerId,
                "splits", List.of(Map.of("userId", debtorId, "amount", "10.00")));
        assertEquals(201, sendExpense(group.groupId(), owner, payload, UUID.randomUUID().toString()).statusCode());
        assertEquals(401, send("DELETE", base, null, null).statusCode());
        assertEquals(404, send("DELETE", base, token(outsider.memberIds().get(0)), null).statusCode());
        assertEquals(403, send("DELETE", base, token(debtorId), null).statusCode());
        assertEquals(403, send("DELETE", base + "/members/" + freshId, token(debtorId), null).statusCode());
        assertEquals(409, send("DELETE", base, owner, null).statusCode());
        assertEquals(409, send("POST", base + "/leave", token(debtorId), null).statusCode());
        assertEquals(409, send("POST", base + "/leave", owner, null).statusCode());
        assertEquals(204, send("DELETE", base + "/members/" + freshId, owner, null).statusCode());
        assertEquals(404, send("GET", base + "/members", token(freshId), null).statusCode());
        String email = userRepository.findById(freshId).orElseThrow().getEmail();
        var linkResponse = objectMapper.readTree(send("POST", base + "/invite-links", owner, null).body());
        assertEquals(200, send("POST", "/api/invitations/link-accept", token(freshId), Map.of("token",linkResponse.get("token").asText())).statusCode());
        assertEquals(204, send("POST", base + "/leave", token(freshId), null).statusCode());
        assertEquals(201, send("POST", settlementsPath(group.groupId()), token(debtorId), Map.of(
                "fromUserId", debtorId, "toUserId", ownerId, "amount", "10.00", "requestId", UUID.randomUUID().toString())).statusCode());
        assertEquals(409, send("DELETE", base + "/members/" + debtorId, owner, null).statusCode());
        assertEquals(204, send("DELETE", base, owner, null).statusCode());
        assertTrue(expenseGroupRepository.findById(group.groupId()).orElseThrow().isArchived());
        assertEquals(1, expenseRepository.countByGroup_Id(group.groupId()));
        assertEquals(1, settlementCount(group.groupId()));
        assertEquals(200, send("GET", expensesPath(group.groupId()), token(debtorId), null).statusCode());
        assertEquals(409, sendExpense(group.groupId(), owner, payload, UUID.randomUUID().toString()).statusCode());
        assertEquals(409, send("POST", base + "/members", owner, Map.of("email", email)).statusCode());
        assertEquals(409, send("POST", base + "/invitations", owner, Map.of("email", email)).statusCode());
        var dashboard = objectMapper.readTree(send("GET", "/api/dashboard", owner, null).body());
        assertEquals(0, dashboard.get("groups").size());
        assertEquals("0.00", dashboard.get("totalExpenses").asText());
        Fixture empty = createFixture("last-owner", 1);
        assertEquals(204, send("DELETE", "/api/groups/" + empty.groupId(), token(empty.memberIds().get(0)), null).statusCode());
    }

	private Fixture createFixture(String label, int memberCount) {
		String suffix = UUID.randomUUID().toString();
		return transactions.execute(status -> {
			List<Long> memberIds = new ArrayList<>();
			for (int index = 0; index < memberCount; index++) {
				User user = userRepository.saveAndFlush(new User(
						"IT " + label + " " + index,
						"it." + label + "." + index + "." + suffix + "@example.invalid",
						"test-only-hash"));
				memberIds.add(user.getId());
			}
			ExpenseGroup group = expenseGroupRepository.saveAndFlush(
					new ExpenseGroup("IT " + label + " " + suffix, userRepository.getReferenceById(memberIds.get(0))));
			for (Long memberId : memberIds) {
				groupMemberRepository.saveAndFlush(
						new GroupMember(group, userRepository.getReferenceById(memberId)));
			}
			return new Fixture(group.getId(), List.copyOf(memberIds));
		});
	}

	private ExpenseResponse createExactExpense(
			Fixture fixture,
			String description,
			String total,
			List<Map<String, Object>> splits) throws Exception {
		Map<String, Object> request = exactExpenseRequest(
				description,
				new BigDecimal(total),
				fixture.memberIds().get(0),
				splits);
		HttpResponse<String> response = send(
				"POST",
				expensesPath(fixture.groupId()),
				token(fixture.memberIds().get(0)),
				request);
		assertEquals(201, response.statusCode(), response.body());
		return read(response, ExpenseResponse.class);
	}

	private Map<String, Object> exactExpenseRequest(
			String description,
			BigDecimal total,
			long payerId,
			List<Map<String, Object>> splits) {
		return Map.of(
				"description", description,
				"amount", total,
				"paidByUserId", payerId,
				"currencyCode", "INR",
				"splitType", "EXACT",
				"splits", splits);
	}

	private Map<String, Object> split(long userId, String amount) {
		return Map.of("userId", userId, "amount", new BigDecimal(amount));
	}

	private Callable<HttpResponse<String>> settlementRequest(
			Fixture fixture,
			long senderId,
			long receiverId,
			String amount,
			String requestId) {
		Map<String, Object> body = Map.of(
				"fromUserId", senderId,
				"toUserId", receiverId,
				"amount", new BigDecimal(amount),
				"requestId", requestId);
		return () -> send(
				"POST",
				settlementsPath(fixture.groupId()),
				token(senderId),
				body);
	}

	private List<HttpResponse<String>> concurrently(List<Callable<HttpResponse<String>>> requests) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(requests.size());
		CountDownLatch ready = new CountDownLatch(requests.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<HttpResponse<String>>> futures = requests.stream()
					.map(request -> executor.submit(() -> {
						ready.countDown();
						if (!start.await(10, TimeUnit.SECONDS)) {
							throw new IllegalStateException("Concurrent test barrier timed out.");
						}
						return request.call();
					}))
					.toList();
			assertTrue(ready.await(10, TimeUnit.SECONDS), "All concurrent requests must reach the start barrier.");
			start.countDown();
			List<HttpResponse<String>> responses = new ArrayList<>(futures.size());
			for (Future<HttpResponse<String>> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}
			return responses;
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}


    @Test
    void expenseRetryAfterLostResponseReturnsOriginalRecord() throws Exception {
        Fixture fixture = createFixture("expense-retry", 2);
        var ids = fixture.memberIds();
        var payload = exactExpenseRequest("Lost response", new BigDecimal("70368744177664.01"),
                ids.get(0), List.of(split(ids.get(1), "70368744177664.01")));
        String key = UUID.randomUUID().toString();
        var first = sendExpense(fixture.groupId(), token(ids.get(0)), payload, key);
        var retry = sendExpense(fixture.groupId(), token(ids.get(0)), payload, key);
        assertEquals(201, first.statusCode());
        assertEquals(201, retry.statusCode());
        assertEquals(read(first, ExpenseResponse.class).id(), read(retry, ExpenseResponse.class).id());
        assertEquals(first.body(), retry.body());
        assertEquals(new BigDecimal("70368744177664.01"), read(retry, ExpenseResponse.class).amount());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expenses WHERE group_id = ?", Integer.class, fixture.groupId()));
    }


    @Test
    void expenseKeysConflictOnlyWithinTheirScopeAndReplaySnapshotsAfterMutation() throws Exception {
        Fixture fixture = createFixture("key-scope", 2);
        var ids = fixture.memberIds();
        String auth = token(ids.get(0));
        String key = UUID.randomUUID().toString();
        var payload = exactExpenseRequest("Original", new BigDecimal("10.00"),
                ids.get(0), List.of(split(ids.get(1), "10.00")));
        assertEquals(400, sendExpense(fixture.groupId(), auth, payload, null).statusCode());
        assertEquals(400, sendExpense(fixture.groupId(), auth, payload, "invalid").statusCode());
        var first = sendExpense(fixture.groupId(), auth, payload, key);
        assertEquals(201, first.statusCode(), first.body());
        var id = read(first, ExpenseResponse.class).id();
        var changed = exactExpenseRequest("Original", new BigDecimal("11.00"),
                ids.get(0), List.of(split(ids.get(1), "11.00")));
        assertEquals(409, sendExpense(fixture.groupId(), auth, changed, key).statusCode());
        var changedParticipant = exactExpenseRequest("Original", new BigDecimal("10.00"),
                ids.get(0), List.of(split(ids.get(0), "10.00")));
        assertEquals(409, sendExpense(fixture.groupId(), auth, changedParticipant, key).statusCode());
        assertEquals(201, sendExpense(fixture.groupId(), auth, payload, UUID.randomUUID().toString()).statusCode());
        assertEquals(201, sendExpense(fixture.groupId(), token(ids.get(1)), payload, key).statusCode());
        Fixture outsider = createFixture("key-outsider", 1);
        assertEquals(404, sendExpense(fixture.groupId(), token(outsider.memberIds().get(0)), payload, key).statusCode());
        var otherPayload = exactExpenseRequest("Other scope", new BigDecimal("10.00"),
                outsider.memberIds().get(0), List.of(split(outsider.memberIds().get(0), "10.00")));
        assertEquals(201, sendExpense(outsider.groupId(), token(outsider.memberIds().get(0)), otherPayload, key).statusCode());
        assertEquals(200, send("PUT", expensesPath(fixture.groupId()) + "/" + id, auth, changed).statusCode());
        assertEquals(first.body(), sendExpense(fixture.groupId(), auth, payload, key).body());
        assertEquals(204, send("DELETE", expensesPath(fixture.groupId()) + "/" + id, auth, null).statusCode());
        assertEquals(first.body(), sendExpense(fixture.groupId(), auth, payload, key).body());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id = ?",
                Integer.class, fixture.groupId()));
        assertEquals(200, send("GET", balancesPath(fixture.groupId()), auth, null).statusCode());
    }

    @Test
    void concurrentExpenseRetriesCreateOneOutcomeAndDatabaseEnforcesUniqueOperation() throws Exception {
        Fixture fixture = createFixture("key-race", 2);
        var ids = fixture.memberIds();
        var payload = exactExpenseRequest("Concurrent", new BigDecimal("10.00"),
                ids.get(0), List.of(split(ids.get(1), "10.00")));
        String key = UUID.randomUUID().toString();
        var responses = concurrently(List.of(
                () -> sendExpense(fixture.groupId(), token(ids.get(0)), payload, key),
                () -> sendExpense(fixture.groupId(), token(ids.get(0)), payload, key)));
        assertEquals(201, responses.get(0).statusCode(), responses.get(0).body());
        assertEquals(responses.get(0).body(), responses.get(1).body());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id = ?",
                Integer.class, fixture.groupId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO expense_creation_operations(group_id,user_id,operation_key,fingerprint,response_json) "
                + "SELECT group_id,user_id,operation_key,fingerprint,response_json FROM expense_creation_operations WHERE group_id = ?",
                fixture.groupId()));
        assertEquals(200, send("GET", balancesPath(fixture.groupId()), token(ids.get(0)), null).statusCode());
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void expenseRetrySurvivesFreshServiceAndRollsBackFailedCreation() throws Exception {
        Fixture fixture = createFixture("key-rollback", 2);
        var ids = fixture.memberIds();
        var payload = exactExpenseRequest("Rollback", new BigDecimal("10.00"),
                ids.get(0), List.of(split(ids.get(1), "10.00")));
        String key = UUID.randomUUID().toString();
        jdbcTemplate.execute("ALTER TABLE expense_splits ADD CONSTRAINT gate2b_fail_split CHECK (user_id <> " + ids.get(1) + ")");
        try {
            assertEquals(500, sendExpense(fixture.groupId(), token(ids.get(0)), payload, key).statusCode());
        } finally {
            jdbcTemplate.execute("ALTER TABLE expense_splits DROP CHECK gate2b_fail_split");
        }
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id = ?",
                Integer.class, fixture.groupId()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM expense_creation_operations WHERE group_id = ?",
                Integer.class, fixture.groupId()));
        var created = sendExpense(fixture.groupId(), token(ids.get(0)), payload, key);
        assertEquals(201, created.statusCode(), created.body());
        ExpenseService fresh = applicationContext.getAutowireCapableBeanFactory().createBean(ExpenseService.class);
        var request = new CreateExpenseRequest("Rollback", new BigDecimal("10.0"), ids.get(0), null,
                List.of(new CreateExpenseSplitRequest(ids.get(1), new BigDecimal("10.00"))));
        var replay = transactions.execute(status ->
                fresh.createExpense(fixture.groupId(), ids.get(0).toString(), request, "\"" + key + "\""));
        assertNotNull(replay, "Expected the committed expense on replay");
        assertEquals(read(created, ExpenseResponse.class).id(), replay.id());
        assertEquals(200, send("GET", balancesPath(fixture.groupId()), token(ids.get(0)), null).statusCode());
    }

    private HttpResponse<String> sendExpense(long groupId, String token, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + expensesPath(groupId)))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", "\"" + key + "\"");
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

	private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json");
		if ("POST".equals(method) && path.matches("/api/groups/[0-9]+/expenses")) {
            builder.header("Idempotency-Key", "\"" + UUID.randomUUID() + "\"");
        }
        if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (body == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			builder.header("Content-Type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(write(body)));
		}
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private <T> T read(HttpResponse<String> response, Class<T> targetType) throws IOException {
		return objectMapper.readValue(response.body(), targetType);
	}

	private String write(Object body) {
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize integration test request.", exception);
		}
	}

	private String token(long userId) {
		Instant issuedAt = Instant.now();
		var claims = JwtClaimsSet.builder()
				.issuer("https://splitsmart.local")
				.subject(Long.toString(userId))
				.issuedAt(issuedAt)
				.expiresAt(issuedAt.plusSeconds(600))
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(),
				claims)).getTokenValue();
	}

	private MemberBalanceResponse balanceFor(GroupBalanceResponse balances, long userId) {
		return balances.balances().stream()
				.filter(balance -> balance.userId().equals(userId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Expected group member balance was absent."));
	}

	private long settlementCount(long groupId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?",
				Long.class,
				groupId);
		assertNotNull(count);
		return count;
	}

	private String expensesPath(long groupId) {
		return "/api/groups/" + groupId + "/expenses";
	}

	private String balancesPath(long groupId) {
		return "/api/groups/" + groupId + "/balances";
	}

	private String settlementsPath(long groupId) {
		return "/api/groups/" + groupId + "/settlements";
	}

	private static void assertIsolatedContainerConfiguration() {
		assertEquals(DATABASE_NAME, MYSQL.getDatabaseName());
		assertFalse(MYSQL.getDatabaseName().equalsIgnoreCase("splitsmart_dev"));
		if (MYSQL.isRunning()) {
			String jdbcUrl = MYSQL.getJdbcUrl();
			assertTrue(jdbcUrl.startsWith("jdbc:mysql://"));
			assertTrue(jdbcUrl.endsWith("/" + DATABASE_NAME));
			assertFalse(jdbcUrl.contains("splitsmart_dev"));
			assertNotNull(MYSQL.getContainerId());
		}
	}

	private static String newTestSecret() {
		byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		return Base64.getEncoder().encodeToString(secret);
	}

	private record Fixture(long groupId, List<Long> memberIds) {
	}
}
