package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import dev.saq.mediscan.llm.DailyCapGuard;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * The two limits that refuse an upload before a report exists ({@code LLD} 6.6).
 *
 * <p>Both must leave no report row behind. A caller told {@code 429} has to be able to retry
 * cleanly, and a {@code PENDING} report nobody will ever process is worse than no report -
 * the client would poll it until it expired.
 *
 * <p>The limits are lowered here rather than exercised at their production values, because
 * eleven real uploads per test would be slow and would say nothing extra.
 *
 * <p>Each test uses its own client address. The rate limiter's buckets are in-memory and
 * keyed by IP, so they outlive a single test method within the shared context - and a test
 * that drained the budget would then fail its neighbours. Giving each test an address also
 * means the per-IP keying itself is under test rather than assumed.
 */
@TestPropertySource(properties = {
		"mediscan.ratelimit.uploads-per-hour=3",
		"mediscan.llm.daily-cap=2",
})
class UploadLimitsTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ReportRepository reports;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DailyCapGuard capGuard;

	private String sessionToken;

	@BeforeEach
	void reset() {
		fixtures.clear();
		jdbc.sql("delete from llm_usage").update();
		sessionToken = fixtures.sessionToken();
	}

	@Test
	@DisplayName("an upload past the per-IP limit is 429 RATE_LIMITED with Retry-After")
	void rateLimitsUploads() throws Exception {
		for (int upload = 1; upload <= 3; upload++) {
			mockMvc.perform(sample("10.0.0.1"))
					.andExpect(status().isAccepted());
		}

		mockMvc.perform(sample("10.0.0.1"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				// Contract section 1.6: a 429 always says how long to wait.
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	@DisplayName("a rate-limited upload creates no report")
	void rateLimitedUploadCreatesNothing() throws Exception {
		for (int upload = 1; upload <= 3; upload++) {
			mockMvc.perform(sample("10.0.0.2")).andExpect(status().isAccepted());
		}
		long before = reports.count();

		mockMvc.perform(sample("10.0.0.2")).andExpect(status().isTooManyRequests());

		assertThat(reports.count()).isEqualTo(before);
	}

	@Test
	@DisplayName("the rate limit still carries a Problem Details body with a code")
	void rateLimitBodyMatchesContract() throws Exception {
		for (int upload = 1; upload <= 3; upload++) {
			mockMvc.perform(sample("10.0.0.3")).andExpect(status().isAccepted());
		}

		// Rendered by the filter, which never reaches the @RestControllerAdvice - so its
		// shape has to be asserted separately from every other error.
		mockMvc.perform(sample("10.0.0.3"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.title").isNotEmpty())
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("an upload once the daily cap is spent is 429 CAPACITY with Retry-After")
	void refusesWhenCapReached() throws Exception {
		spendCap();

		mockMvc.perform(sample("10.0.0.4"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("CAPACITY"))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	@DisplayName("a capacity refusal creates no report")
	void capacityRefusalCreatesNothing() throws Exception {
		spendCap();
		long before = reports.count();

		mockMvc.perform(sample("10.0.0.5")).andExpect(status().isTooManyRequests());

		// Contract section 4.1: the report is not created, so the caller is not left polling
		// something that can never finish.
		assertThat(reports.count()).isEqualTo(before);
	}

	@Test
	@DisplayName("an upload is accepted while the cap still has room")
	void acceptsBelowCap() throws Exception {
		// One of two spent, so there is still room and the upload must not be refused.
		assertThat(capGuard.tryAcquire()).isTrue();

		mockMvc.perform(sample("10.0.0.6")).andExpect(status().isAccepted());
	}

	/**
	 * Spends today's budget through the guard itself.
	 *
	 * <p>Not an INSERT with Postgres' current_date: the guard keys on the JVM's UTC date, and
	 * the two disagree whenever the database server is not on UTC. Going through the guard
	 * also means the pre-check is tested against the real counter rather than a row this test
	 * invented.
	 */
	private void spendCap() {
		assertThat(capGuard.tryAcquire()).isTrue();
		assertThat(capGuard.tryAcquire()).isTrue();
		assertThat(capGuard.isCapReached()).isTrue();
	}

	private RequestBuilder sample(String clientIp) {
		return post("/api/reports")
				.header("X-Session-Token", sessionToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel", "consent": true}""")
				.with(request -> {
					request.setRemoteAddr(clientIp);
					return request;
				});
	}
}
