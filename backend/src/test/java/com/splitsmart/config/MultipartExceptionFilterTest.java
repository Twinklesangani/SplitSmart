package com.splitsmart.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.ServletException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.multipart.MultipartException;

class MultipartExceptionFilterTest {

	@Test
	void convertsMultipartParserFailureToBadRequest() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/groups");
		var response = new MockHttpServletResponse();
		var objectMapper = new ObjectMapper().findAndRegisterModules();
		var filter = new MultipartExceptionFilter(objectMapper);

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
			throw new ServletException("Request failed.", new MultipartException("Malformed multipart request."));
		});

		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertEquals(400, response.getStatus());
		assertEquals("malformed_request", body.path("code").asText());
		assertEquals("/api/groups", body.path("path").asText());
	}
}
