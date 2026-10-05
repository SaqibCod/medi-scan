package dev.saq.mediscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import dev.saq.mediscan.support.PostgresTestBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /actuator/health} (contract section 7).
 *
 * <p>Also the suite's context-load test: if any bean, migration or configuration binding is
 * broken, this fails first and with the clearest message.
 *
 * <p>The client calls this on page load to wake the backend, so it has to work for an
 * anonymous caller - which is the easiest thing in the world to break by tightening the
 * filter chain.
 */
class HealthEndpointTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	@DisplayName("is public and reports UP")
	void reportsUp() throws Exception {
		MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("UP");
	}

	@Test
	@DisplayName("no other actuator endpoint is exposed")
	void otherEndpointsNotExposed() throws Exception {
		// management.endpoints.web.exposure.include is health only. env and beans would leak
		// configuration, including which variables are set.
		for (String endpoint : new String[] { "/actuator/env", "/actuator/beans", "/actuator/metrics" }) {
			int status = mockMvc.perform(get(endpoint)).andReturn().getResponse().getStatus();
			assertThat(status)
					.as("%s must not be served", endpoint)
					.isNotEqualTo(200);
		}
	}
}
