package dev.saq.mediscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * The per-IP limit on the auth endpoints (contract section 1.6, LLD 9).
 *
 * <p>Lowered to three for this class, so the test spends three requests proving the limit
 * instead of ten. The behaviour under test is "the bucket runs out and says how long to wait",
 * and the exact capacity is configuration.
 *
 * <p>Deliberately verifies that sign-in and refresh have <em>separate</em> buckets: a burst of
 * auto sign-ins in new tabs must not consume the allowance a page reload needs for its
 * refresh, and one shared bucket would make that happen on exactly the busy days it matters.
 */
@TestPropertySource(properties = "mediscan.ratelimit.auth-per-minute=3")
class AuthRateLimitTest extends PostgresTestBase {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("the fourth sign-in attempt in a minute is 429 with Retry-After")
	void signInIsLimited() throws Exception {
		String ip = "10.1.0.1";

		for (int attempt = 1; attempt <= 3; attempt++) {
			// Each is rejected on its merits - the token is nonsense - but still costs a
			// token from the bucket, which is the point: a limit that only counted successes
			// would not slow down someone guessing.
			signIn(ip).andExpect(status().isUnauthorized());
		}

		signIn(ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	@Test
	@DisplayName("refresh has its own bucket, so sign-ins cannot exhaust it")
	void refreshHasItsOwnBucket() throws Exception {
		String ip = "10.1.0.2";

		// Spend the sign-in allowance entirely.
		for (int attempt = 1; attempt <= 4; attempt++) {
			signIn(ip);
		}

		// Refresh must still answer on its own merits rather than 429.
		refresh(ip)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
	}

	@Test
	@DisplayName("the fourth refresh attempt in a minute is 429")
	void refreshIsLimited() throws Exception {
		String ip = "10.1.0.3";

		for (int attempt = 1; attempt <= 3; attempt++) {
			refresh(ip).andExpect(status().isUnauthorized());
		}

		refresh(ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
	}

	@Test
	@DisplayName("one address running out does not affect another")
	void bucketsArePerAddress() throws Exception {
		String exhausted = "10.1.0.4";
		for (int attempt = 1; attempt <= 4; attempt++) {
			signIn(exhausted);
		}
		signIn(exhausted).andExpect(status().isTooManyRequests());

		// Everyone behind one NAT would otherwise be locked out by a single noisy client.
		signIn("10.1.0.5").andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("logout is not rate limited")
	void logoutIsNotLimited() throws Exception {
		// A client that cannot log out is stuck showing a signed-in header it cannot clear,
		// and logout only revokes a family the caller already holds a token for - so the
		// limit would protect nothing and break something (LLD 9).
		String ip = "10.1.0.6";

		for (int attempt = 1; attempt <= 8; attempt++) {
			mockMvc.perform(post("/api/auth/logout")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"refreshToken\":\"never-existed\"}")
					.with(from(ip)))
					.andExpect(status().isNoContent());
		}
	}

	private ResultActions signIn(String ip) throws Exception {
		return mockMvc.perform(post("/api/auth/google")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"not-a-real-google-token\"}")
				.with(from(ip)));
	}

	private ResultActions refresh(String ip) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"not-a-real-refresh-token\"}")
				.with(from(ip)));
	}

	/**
	 * Sends the request from a given address.
	 *
	 * <p>Buckets live in memory for the life of the context and key on the client address, so
	 * tests in this class would otherwise share one bucket and the fourth test to run would
	 * fail on whatever the first three had already spent. A distinct address per test keeps
	 * each one independent of the order they run in.
	 */
	private static RequestPostProcessor from(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}
}
