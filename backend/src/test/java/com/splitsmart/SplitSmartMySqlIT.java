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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.CreateExpenseRequest;
import com.splitsmart.dto.CreateExpenseSplitRequest;
import com.splitsmart.dto.ExpenseResponse;
import com.splitsmart.dto.GroupBalanceResponse;
import com.splitsmart.dto.MemberBalanceResponse;
import com.splitsmart.dto.SettlementSuggestionResponse;
import com.splitsmart.entity.Expense;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql-it")
class SplitSmartMySqlIT {

	private static final String DATABASE_NAME = "splitsmart_it";
	private static final String MYSQL_IMAGE = "mysql:8.4.13";
	private static final String TEST_JWT_SECRET = newTestSecret();
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
			.withDatabaseName(DATABASE_NAME)
			.withUsername("splitsmart_it_user")
			.withPassword(newTestSecret())
			.withStartupTimeout(Duration.ofMinutes(2));

	static {
		try {
			MYSQL.start();
			assertIsolatedContainerConfiguration();
		} catch (RuntimeException | Error failure) {
			MYSQL.stop();
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
	private PlatformTransactionManager transactionManager;

	@SpyBean
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
		MYSQL.stop();
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
		assertEquals(3, flyway.info().applied().length);
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
			assertTrue(jdbcTemplate.queryForObject("SELECT VERSION()", String.class).startsWith("8.4."));
		}

		assertEquals(12, jdbcTemplate.queryForObject(
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

		assertThrows(
				org.springframework.dao.DataIntegrityViolationException.class,
				() -> expenseService.updateExpense(
						fixture.groupId(),
						original.id(),
						ids.get(0).toString(),
						replacement));

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

	private Fixture createFixture(String label, int memberCount) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		String suffix = UUID.randomUUID().toString();
		return transaction.execute(status -> {
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

	private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json");
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
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?",
				Long.class,
				groupId);
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
