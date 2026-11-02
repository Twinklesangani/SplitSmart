package com.splitsmart.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.links.Link;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

@Configuration
@OpenAPIDefinition(info = @Info(
		title = "SplitSmart API",
		version = "1.0.0",
		description = "Group expense, balance, and settlement API."))
@SecurityScheme(
		name = "bearerAuth",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT")
public class OpenApiConfig {

	@Bean
	OpenApiCustomizer expenseSplitContract() {
		return openApi -> {
			for (String schemaName : List.of(
					"CreateExpenseRequest", "CreateExpenseSplitRequest", "CreateSettlementRequest")) {
				Schema<?> amount = (Schema<?>) Objects.requireNonNull(
						openApi.getComponents().getSchemas().get(schemaName),
						"Missing request schema " + schemaName)
						.getProperties().get("amount");
				Objects.requireNonNull(amount, "Missing amount in request schema " + schemaName)
						.setMaximum(null);
				amount.setExclusiveMaximumValue(new BigDecimal("100000000000000000"));
			}

			openApi.getComponents().getSchemas().forEach((name, component) -> {
				Schema<?> schema = component;
				if (!name.endsWith("Response") || schema.getProperties() == null) return;
				schema.getProperties().replaceAll((fieldName, field) -> {
					if ("number".equals(field.getType())) {
						return new StringSchema().pattern("^-?(0|[1-9][0-9]*)(\\.[0-9]{1,2})?$")
								.description("Exact decimal money. Parse without floating-point conversion.");
					}
					return field;
				});
			});

			Schema<?> settlementResponse = Objects.requireNonNull(
					openApi.getComponents().getSchemas().get("SettlementResponse"),
					"Missing SettlementResponse schema");
			Schema<?> createdAt = (Schema<?>) settlementResponse.getProperties().get("createdAt");
			Objects.requireNonNull(createdAt, "Missing SettlementResponse.createdAt schema")
					.setFormat("local-date-time");

			Schema<?> exactConstraints = new Schema<>();
			exactConstraints.setType("object");
			exactConstraints.addProperty("splitType", new StringSchema()._enum(List.of("EXACT")));
			ArraySchema exactSplits = new ArraySchema();
			exactSplits.setMinItems(1);
			exactConstraints.addProperty("splits", exactSplits);
			Schema<?> noParticipants = new Schema<>();
			noParticipants.setNot(new ArraySchema());
			exactConstraints.addProperty("participantUserIds", noParticipants);
			exactConstraints.setRequired(List.of("splits"));

			Schema<?> equalConstraints = new Schema<>();
			equalConstraints.setType("object");
			equalConstraints.addProperty("splitType", new StringSchema()._enum(List.of("EQUAL")));
			Schema<?> noExactSplits = new Schema<>();
			noExactSplits.setNot(new ArraySchema());
			equalConstraints.addProperty("splits", noExactSplits);
			ArraySchema equalParticipants = new ArraySchema();
			equalParticipants.setMinItems(1);
			equalConstraints.addProperty("participantUserIds", equalParticipants);
			equalConstraints.setRequired(List.of("splitType", "participantUserIds"));

			openApi.getComponents().addSchemas("CreateExpenseRequestExact",
					expenseRequestVariant("CreateExpenseRequest", exactConstraints));
			openApi.getComponents().addSchemas("CreateExpenseRequestEqual",
					expenseRequestVariant("CreateExpenseRequest", equalConstraints));

			Schema<?> requestSchema = new ComposedSchema()
					.addOneOfItem(schemaReference("CreateExpenseRequestExact"))
					.addOneOfItem(schemaReference("CreateExpenseRequestEqual"));
			setExpenseRequestSchema(openApi, "/api/groups/{groupId}/expenses", false, requestSchema);
			setExpenseRequestSchema(openApi, "/api/groups/{groupId}/expenses/{expenseId}", true, requestSchema);

			addLink(openApi, "/api/groups", "post", "201", "AddStatefulMember", "addMember",
					Map.of("groupId", "$response.body#/id"),
					Map.of("email", "stateful-member@example.invalid"));
			addLink(openApi, "/api/groups/{groupId}/members", "post", "201",
					"ListMembersAfterAdd", "listMembers",
					Map.of("groupId", "$request.path.groupId"), null);
			addLink(openApi, "/api/groups/{groupId}/members", "get", "200",
					"CreateEqualExpenseFromMembers", "createExpense",
					Map.of("groupId", "$request.path.groupId"),
					Map.of(
							"description", "Schemathesis linked equal expense",
							"amount", new BigDecimal("10.00"),
							"paidByUserId", "$response.body#/1/userId",
							"currencyCode", "INR",
							"splitType", "EQUAL",
							"participantUserIds", List.of(
									"$response.body#/0/userId",
									"$response.body#/1/userId")));
			addLink(openApi, "/api/groups", "get", "200",
					"ListMembersForFirstGroup", "listMembers",
					Map.of("groupId", "$response.body#/0/id"), null);
			addLink(openApi, "/api/groups/{groupId}/expenses", "post", "201",
					"GetCreatedExpense", "getExpense",
					Map.of(
							"groupId", "$response.body#/groupId",
							"expenseId", "$response.body#/id"), null);
			addLink(openApi, "/api/groups/{groupId}/expenses", "post", "201",
					"GetBalancesAfterExpense", "getGroupBalances",
					Map.of("groupId", "$response.body#/groupId"), null);
			addLink(openApi, "/api/groups/{groupId}/balances", "get", "200",
					"GetSettlementSuggestions", "getSuggestions",
					Map.of("groupId", "$response.body#/groupId"), null);
			addLink(openApi, "/api/groups/{groupId}/settlement-suggestions", "get", "200",
					"RecordSuggestedSettlement", "createSettlement",
					Map.of("groupId", "$response.body#/groupId"),
					Map.of(
							"fromUserId", "$response.body#/suggestions/0/fromUserId",
							"toUserId", "$response.body#/suggestions/0/toUserId",
							"amount", "$response.body#/suggestions/0/amount",
							"requestId", "00000000-0000-4000-8000-000000000001",
							"note", "Schemathesis stateful settlement"));
			addLink(openApi, "/api/groups/{groupId}/settlements", "post", "201",
					"GetSettlementHistory", "listSettlements",
					Map.of("groupId", "$response.body#/groupId"), null);
			addLink(openApi, "/api/groups/{groupId}/settlements", "post", "201",
					"GetBalancesAfterSettlement", "getGroupBalances",
					Map.of("groupId", "$response.body#/groupId"), null);
			addLink(openApi, "/api/groups/{groupId}/expenses/{expenseId}", "get", "200",
					"UpdateExpense", "updateExpense",
					Map.of(
							"groupId", "$response.body#/groupId",
							"expenseId", "$response.body#/id"),
					Map.of(
							"description", "Updated Schemathesis linked expense",
							"amount", "$response.body#/amount",
							"paidByUserId", "$response.body#/paidByUserId",
							"currencyCode", "INR",
							"splitType", "EQUAL",
							"participantUserIds", List.of(
									"$response.body#/splits/0/userId",
									"$response.body#/splits/1/userId")));
			addLink(openApi, "/api/groups/{groupId}/expenses/{expenseId}", "put", "200",
					"ReadUpdatedExpense", "getExpense",
					Map.of(
							"groupId", "$response.body#/groupId",
							"expenseId", "$response.body#/id"), null);
		};
	}

	private static void addLink(
			OpenAPI openApi,
			String sourcePath,
			String sourceMethod,
			String sourceStatus,
			String linkName,
			String targetOperation,
			Map<String, String> parameters,
			Object requestBody) {
		Operation source = operation(openApi, sourcePath, sourceMethod);
		ApiResponse response = Objects.requireNonNull(source.getResponses().get(sourceStatus),
				"Missing " + sourceStatus + " response for " + sourceMethod + " " + sourcePath);
		Link link = new Link().operationId(targetOperation);
		link.setParameters(parameters);
		if (requestBody != null) {
			link.setRequestBody(requestBody);
		}
		response.addLink(linkName, link);
	}

	private static Operation operation(OpenAPI openApi, String path, String method) {
		var pathItem = Objects.requireNonNull(openApi.getPaths().get(path), "Missing API path " + path);
		Operation operation = switch (method) {
			case "get" -> pathItem.getGet();
			case "post" -> pathItem.getPost();
			case "put" -> pathItem.getPut();
			default -> throw new IllegalArgumentException("Unsupported OpenAPI link source method " + method);
		};
		return Objects.requireNonNull(operation, "Missing " + method + " operation for " + path);
	}

	private static ComposedSchema expenseRequestVariant(String baseSchemaName, Schema<?> constraints) {
		ComposedSchema schema = new ComposedSchema();
		schema.addAllOfItem(schemaReference(baseSchemaName));
		schema.addAllOfItem(constraints);
		return schema;
	}

	private static Schema<?> schemaReference(String schemaName) {
		Schema<?> reference = new Schema<>();
		reference.set$ref("#/components/schemas/" + schemaName);
		return reference;
	}

	private static void setExpenseRequestSchema(
			OpenAPI openApi,
			String path,
			boolean update,
			Schema<?> requestSchema) {
		var pathItem = Objects.requireNonNull(openApi.getPaths().get(path), "Missing expense API path " + path);
		Operation operation = update ? pathItem.getPut() : pathItem.getPost();
		Objects.requireNonNull(operation, "Missing expense API operation for " + path);
		RequestBody requestBody = Objects.requireNonNull(operation.getRequestBody(),
				"Missing expense request body for " + path);
		MediaType json = Objects.requireNonNull(requestBody.getContent().get("application/json"),
				"Missing JSON request content for " + path);
		json.setSchema(requestSchema);
	}
}
