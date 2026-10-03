package medi_scan.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import medi_scan.backend.support.PostgresTestBase;

/**
 * CORS against contract section 1.4.
 *
 * <p>Worth testing rather than eyeballing: the frontend and API are on different origins, so a
 * mistake here does not degrade the app, it breaks every call in the browser while leaving
 * curl working perfectly.
 */
class CorsTest extends PostgresTestBase {

	private static final String ALLOWED_ORIGIN = "http://localhost:3000";
	private static final String OTHER_ORIGIN = "https://evil.example.com";

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("preflight from the allowed origin is accepted")
	void preflightFromAllowedOrigin() throws Exception {
		MvcResult result = mockMvc.perform(options("/api/sessions")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
				.isEqualTo(ALLOWED_ORIGIN);
	}

	@Test
	@DisplayName("preflight from any other origin is rejected")
	void preflightFromOtherOriginRejected() throws Exception {
		MvcResult result = mockMvc.perform(options("/api/sessions")
				.header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
	}

	@Test
	@DisplayName("allows exactly the methods and request headers in the contract")
	void advertisesContractMethodsAndHeaders() throws Exception {
		MvcResult result = mockMvc.perform(options("/api/sessions")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, X-Session-Token"))
				.andReturn();

		String allowedMethods = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
		assertThat(allowedMethods).contains("GET", "POST", "DELETE", "OPTIONS");

		String allowedHeaders = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
		assertThat(allowedHeaders).contains("Content-Type", "X-Session-Token");
	}

	@Test
	@DisplayName("exposes the response headers the client needs to read")
	void exposesResponseHeaders() throws Exception {
		MvcResult result = mockMvc.perform(options("/api/sessions")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andReturn();

		// Without these the browser hides them from JavaScript, so the error UI could not
		// show a request id and the retry logic could not read Retry-After.
		String exposed = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
		assertThat(exposed).contains(RequestIdFilter.HEADER, "Retry-After", "Location", "WWW-Authenticate");
	}

	@Test
	@DisplayName("does not enable credentialed mode, because no cookie carries a credential")
	void doesNotAllowCredentials() throws Exception {
		MvcResult result = mockMvc.perform(options("/api/sessions")
				.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
	}
}
