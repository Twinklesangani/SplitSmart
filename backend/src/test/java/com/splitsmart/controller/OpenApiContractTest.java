package com.splitsmart.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class OpenApiContractTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void documentsValidationBoundariesAndProtectedOperations() throws Exception {
		var response = mockMvc.perform(get("/api/openapi"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse();
		JsonNode document = new ObjectMapper()
				.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
				.readTree(response.getContentAsString());
		JsonNode schemas = document.path("components").path("schemas");

		assertEquals("3.1.0", document.path("openapi").asText());
		JsonNode registrationName = schemas.path("RegisterRequest").path("properties").path("name");
		assertEquals(1, registrationName.path("minLength").asInt(), registrationName.toString());
		assertEquals(120, registrationName.path("maxLength").asInt(), registrationName.toString());
		assertEquals("\\S", registrationName.path("pattern").asText(), registrationName.toString());
		JsonNode registrationPassword = schemas.path("RegisterRequest").path("properties").path("password");
		assertEquals(8, registrationPassword.path("minLength").asInt(), registrationPassword.toString());
		assertEquals(72, registrationPassword.path("maxLength").asInt(), registrationPassword.toString());
		assertTrue(registrationPassword.path("description").asText().contains("72 UTF-8 bytes"),
				registrationPassword.toString());
		assertTrue(registrationPassword.path("description").asText().contains("count characters, not UTF-8 bytes"),
				registrationPassword.toString());
		assertEquals("local-date-time",
				schemas.path("SettlementResponse").path("properties").path("createdAt").path("format").asText());

		JsonNode expense = schemas.path("CreateExpenseRequest");
		JsonNode amount = expense.path("properties").path("amount");
		assertEquals(0, amount.path("minimum").decimalValue().compareTo(new BigDecimal("0.01")));
		assertEquals(0, amount.path("exclusiveMaximum").decimalValue()
				.compareTo(new BigDecimal("100000000000000000")), amount.toString());
		assertTrue(amount.path("maximum").isMissingNode(), amount.toString());
		assertEquals(0, amount.path("multipleOf").decimalValue().compareTo(new BigDecimal("0.01")));
		assertEquals(0, schemas.path("CreateExpenseSplitRequest").path("properties").path("amount")
				.path("exclusiveMaximum").decimalValue()
				.compareTo(new BigDecimal("100000000000000000")));
		assertEquals(0, schemas.path("CreateSettlementRequest").path("properties").path("amount")
				.path("exclusiveMaximum").decimalValue()
				.compareTo(new BigDecimal("100000000000000000")));
		assertEquals(1, expense.path("properties").path("splits").path("minItems").asInt());
        assertEquals(128, expense.path("properties").path("splits").path("maxItems").asInt());
        assertEquals(128, expense.path("properties").path("participantUserIds").path("maxItems").asInt());
		assertEquals(1, expense.path("properties").path("participantUserIds").path("minItems").asInt());
		assertTrue(containsType(expense.path("properties").path("splits"), "null"));
		assertTrue(containsType(expense.path("properties").path("participantUserIds"), "null"));
		assertEquals(1, expense.path("properties").path("paidByUserId").path("minimum").asInt());

		for (String[] operation : List.of(
				new String[] { "/api/groups/{groupId}/expenses", "post" },
				new String[] { "/api/groups/{groupId}/expenses/{expenseId}", "put" })) {
			JsonNode requestSchema = document.path("paths").path(operation[0]).path(operation[1])
					.path("requestBody").path("content").path("application/json").path("schema");
			assertEquals(2, requestSchema.path("oneOf").size(), requestSchema.toString());
			assertTrue(requestSchema.path("oneOf").get(0).path("$ref").asText()
					.endsWith("CreateExpenseRequestExact"));
			assertTrue(requestSchema.path("oneOf").get(1).path("$ref").asText()
					.endsWith("CreateExpenseRequestEqual"));
		}
		JsonNode exactExpense = schemas.path("CreateExpenseRequestExact").path("allOf").get(1);
		assertEquals(Set.of("splits"), Set.copyOf(toStringList(exactExpense.path("required"))));
		assertEquals(List.of("EXACT"),
				toStringList(exactExpense.path("properties").path("splitType").path("enum")));
		assertTrue(containsType(exactExpense.path("properties").path("participantUserIds").path("not"), "array"));
		JsonNode equalExpense = schemas.path("CreateExpenseRequestEqual").path("allOf").get(1);
		assertEquals(Set.of("splitType", "participantUserIds"),
				Set.copyOf(toStringList(equalExpense.path("required"))));
		assertEquals(List.of("EQUAL"),
				toStringList(equalExpense.path("properties").path("splitType").path("enum")));
		assertTrue(containsType(equalExpense.path("properties").path("splits").path("not"), "array"));

		for (String[] operation : List.of(
				new String[] { "/api/groups/{groupId}/members", "post" },
				new String[] { "/api/groups/{groupId}/members", "get" },
				new String[] { "/api/groups/{groupId}/expenses", "post" },
				new String[] { "/api/groups/{groupId}/expenses", "get" },
				new String[] { "/api/groups/{groupId}/expenses/{expenseId}", "get" },
				new String[] { "/api/groups/{groupId}/expenses/{expenseId}", "put" },
				new String[] { "/api/groups/{groupId}/expenses/{expenseId}", "delete" },
				new String[] { "/api/groups/{groupId}/balances", "get" },
				new String[] { "/api/groups/{groupId}/settlements", "post" },
				new String[] { "/api/groups/{groupId}/settlements", "get" },
				new String[] { "/api/groups/{groupId}/settlement-suggestions", "get" })) {
			assertTrue(document.path("paths").path(operation[0]).path(operation[1]).path("responses").has("400"),
					operation[1] + " " + operation[0] + " must document invalid path IDs.");
		}

		JsonNode requestId = schemas.path("CreateSettlementRequest").path("properties").path("requestId");
		assertEquals(36, requestId.path("minLength").asInt());
		assertEquals(36, requestId.path("maxLength").asInt());
		assertTrue(requestId.path("pattern").asText().contains("[0-9A-Fa-f]"));

		JsonNode groupCreation = document.path("paths").path("/api/groups").path("post");
		assertTrue(groupCreation.path("security").get(0).has("bearerAuth"));
		assertEquals("http", document.path("components").path("securitySchemes").path("bearerAuth")
				.path("type").asText());
		assertEquals("JWT", document.path("components").path("securitySchemes").path("bearerAuth")
				.path("bearerFormat").asText());

		int operationCount = 0;
		int linkCount = 0;
		var methods = List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
		var pathItems = document.path("paths").elements();
		while (pathItems.hasNext()) {
			JsonNode path = pathItems.next();
			for (String method : methods) {
				if (path.has(method)) {
					operationCount++;
					var responses = path.path(method).path("responses").elements();
					while (responses.hasNext()) {
						linkCount += responses.next().path("links").size();
					}
				}
			}
		}
		assertEquals(25, operationCount);
		assertEquals(12, linkCount);

		assertEquals("addMember", groupCreation.path("responses").path("201").path("links")
				.path("AddStatefulMember").path("operationId").asText());
		assertEquals("$response.body#/id", groupCreation.path("responses").path("201").path("links")
				.path("AddStatefulMember").path("parameters").path("groupId").asText());
		assertEquals("stateful-member@example.invalid",
				groupCreation.path("responses").path("201").path("links")
						.path("AddStatefulMember").path("requestBody").path("email").asText());
		JsonNode equalExpenseLink = document.path("paths").path("/api/groups/{groupId}/members")
				.path("get").path("responses").path("200").path("links").path("CreateEqualExpenseFromMembers");
		assertEquals("createExpense", equalExpenseLink.path("operationId").asText());
		assertEquals("$response.body#/1/userId",
				equalExpenseLink.path("requestBody").path("paidByUserId").asText());
		JsonNode groupsLink = document.path("paths").path("/api/groups").path("get")
				.path("responses").path("200").path("links").path("ListMembersForFirstGroup");
		assertEquals("listMembers", groupsLink.path("operationId").asText());
		assertEquals("$response.body#/0/id", groupsLink.path("parameters").path("groupId").asText());
		assertEquals("createSettlement",
				document.path("paths").path("/api/groups/{groupId}/settlement-suggestions")
						.path("get").path("responses").path("200").path("links")
						.path("RecordSuggestedSettlement").path("operationId").asText());
	}

	private static boolean containsType(JsonNode schema, String expectedType) {
		JsonNode type = schema.path("type");
		if (type.isTextual()) {
			return expectedType.equals(type.asText());
		}
		for (JsonNode value : type) {
			if (expectedType.equals(value.asText())) {
				return true;
			}
		}
		return false;
	}

	private static List<String> toStringList(JsonNode values) {
		List<String> result = new java.util.ArrayList<>();
		for (JsonNode value : values) {
			result.add(value.asText());
		}
		return result;
	}
}
