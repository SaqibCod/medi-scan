package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.TestGoogleIssuer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Granting {@code ADMIN} through {@code ADMIN_GOOGLE_SUBS}, which is the only way it can be
 * granted (CLAUDE.md rule 17).
 *
 * <p>Its own test class because it needs the property set before the context starts, and the
 * subject therefore has to be a fixed value rather than a per-test UUID.
 */
@Import(TestGoogleVerifierConfig.class)
@TestPropertySource(properties = "mediscan.auth.admin-subjects=" + AdminSubjectPromotionTest.ADMIN_SUB)
class AdminSubjectPromotionTest extends PostgresTestBase {

	static final String ADMIN_SUB = "116947382910473829104";
	private static final String ORDINARY_SUB = "209384710293847102938";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestGoogleIssuer google;

	@Autowired
	private JdbcClient jdbc;

	@Test
	@DisplayName("a listed subject signs in as ADMIN, and it is stored")
	void listedSubjectBecomesAdmin() throws Exception {
		signIn(google.validToken(ADMIN_SUB, "The Admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.role").value("ADMIN"));

		assertThat(storedRole(ADMIN_SUB)).isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("an unlisted subject signs in as USER")
	void unlistedSubjectStaysUser() throws Exception {
		signIn(google.validToken(ORDINARY_SUB, "Ordinary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.role").value("USER"));

		assertThat(storedRole(ORDINARY_SUB)).isEqualTo("USER");
	}

	@Test
	@DisplayName("the admin's access token reaches an admin-only endpoint")
	void adminTokenOpensAdminRoutes() throws Exception {
		String accessToken = JSON
				.readTree(signIn(google.validToken(ADMIN_SUB, "The Admin"))
						.andReturn().getResponse().getContentAsString())
				.get("accessToken").asString();

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/admin/support/ping")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a role the config no longer agrees with is corrected on refresh")
	void refreshRecomputesRole() throws Exception {
		// The config lists only ADMIN_SUB, so an account stored as ADMIN under a different
		// subject must be demoted on its next refresh - that is what makes removing a sub
		// take effect within one access token lifetime rather than needing a new sign-in
		// (docs/dataflow.md section 3.2).
		JsonNode signedIn = JSON.readTree(signIn(google.validToken(ORDINARY_SUB, "Ordinary"))
				.andReturn().getResponse().getContentAsString());

		jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE google_sub = :sub")
				.param("sub", ORDINARY_SUB)
				.update();

		mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + signedIn.get("refreshToken").asString() + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.role").value("USER"));

		assertThat(storedRole(ORDINARY_SUB)).isEqualTo("USER");
	}

	@Test
	@DisplayName("a listed subject stays ADMIN across a refresh")
	void refreshKeepsAdmin() throws Exception {
		JsonNode signedIn = JSON.readTree(signIn(google.validToken(ADMIN_SUB, "The Admin"))
				.andReturn().getResponse().getContentAsString());

		mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + signedIn.get("refreshToken").asString() + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.role").value("ADMIN"));
	}

	private ResultActions signIn(String idToken) throws Exception {
		return mockMvc.perform(post("/api/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"" + idToken + "\"}"));
	}

	private String storedRole(String googleSub) {
		return jdbc.sql("SELECT role FROM app_user WHERE google_sub = :sub")
				.param("sub", googleSub)
				.query(String.class)
				.single();
	}
}
