package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.session.GuestAuthFilter;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;
import dev.saq.mediscan.support.TestTokens;
import dev.saq.mediscan.user.UserRole;

/**
 * The filter chain itself: who gets in, with which credential, and what a rejection says.
 *
 * <p>Uses the {@code /api/reports/support/whoami} probe so each case can assert what the
 * handler was actually given, not merely that the status was not 401. "Did not 401" would pass
 * for a request that got in as the wrong owner, which is the failure that matters most here.
 */
class SecurityChainTest extends PostgresTestBase {

	private static final String WHOAMI = "/api/reports/support/whoami";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ReportFixtures fixtures;

	@Autowired
	private ApplicationContext context;

	// --- structural guarantees ----------------------------------------------

	@Test
	@DisplayName("exactly one JwtDecoder bean exists")
	void singleJwtDecoderBean() {
		// Google's decoder is built inside GoogleIdTokenVerifier on purpose. A second bean of
		// this type would make Boot's autoconfiguration and the oauth2ResourceServer decoder
		// lookup ambiguous, and the failure would be a startup error on some other day (D9).
		assertThat(context.getBeanNamesForType(JwtDecoder.class))
				.containsExactly("accessTokenDecoder");
	}

	@Test
	@DisplayName("the bearer filter runs before the guest filter")
	void bearerFilterRunsBeforeGuestFilter() {
		// Phase 1 anchored the guest filter on UsernamePasswordAuthenticationFilter, which
		// FilterOrderRegistration sorts *before* the bearer filter - so this ordering is the
		// thing that had to change, and it is worth asserting rather than assuming (D5).
		List<Class<?>> filters = filterClasses();

		int bearerAt = indexOf(filters, BearerTokenAuthenticationFilter.class);
		int guestAt = indexOf(filters, GuestAuthFilter.class);

		assertThat(bearerAt).as("BearerTokenAuthenticationFilter is in the chain").isNotNegative();
		assertThat(guestAt).as("GuestAuthFilter is in the chain").isNotNegative();
		assertThat(bearerAt).as("bearer before guest").isLessThan(guestAt);
	}

	// --- guests --------------------------------------------------------------

	@Test
	@DisplayName("a valid session token authenticates as a guest owner")
	void guestTokenResolvesToGuestOwner() throws Exception {
		String token = fixtures.sessionToken();
		UUID sessionId = fixtures.sessionIdFor(token);

		mockMvc.perform(get(WHOAMI).header(GuestAuthFilter.HEADER, token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerType").value("GUEST"))
				.andExpect(jsonPath("$.ownerId").value(sessionId.toString()));
	}

	@Test
	@DisplayName("no credential at all on a report route is SESSION_INVALID")
	void noCredentialIsSessionInvalid() throws Exception {
		mockMvc.perform(get(WHOAMI))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	@Test
	@DisplayName("an unknown session token is SESSION_INVALID, not a server error")
	void unknownSessionTokenIsSessionInvalid() throws Exception {
		mockMvc.perform(get(WHOAMI).header(GuestAuthFilter.HEADER, "not-a-real-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("SESSION_INVALID"));
	}

	// --- bearer tokens -------------------------------------------------------

	@Test
	@DisplayName("a valid access token authenticates as a user owner")
	void bearerTokenResolvesToUserOwner() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerType").value("USER"))
				.andExpect(jsonPath("$.ownerId").value(userId.toString()));
	}

	@Test
	@DisplayName("an expired access token is TOKEN_EXPIRED with the WWW-Authenticate header")
	void expiredTokenIsTokenExpired() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithCurrentKey().expiredFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer error=\"invalid_token\""));
	}

	@Test
	@DisplayName("an unknown kid is TOKEN_INVALID, not TOKEN_EXPIRED")
	void unknownKidIsTokenInvalid() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithUnknownKey("retired-k0")
				.validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer error=\"invalid_token\""));
	}

	@Test
	@DisplayName("a token with no kid is TOKEN_INVALID")
	void missingKidIsTokenInvalid() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithoutKeyId().validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	@DisplayName("a garbled Authorization header is TOKEN_INVALID")
	void garbledBearerIsTokenInvalid() throws Exception {
		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	@DisplayName("a token signed with a previous but still-listed key is accepted")
	void previousKeyStillWorks() throws Exception {
		UUID userId = fixtures.user();
		String token = TestTokens.signingWithPreviousKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerType").value("USER"));
	}

	// --- rule 16: no fallback -----------------------------------------------

	@Test
	@DisplayName("a bad bearer token plus a valid guest token is TOKEN_INVALID, never guest access")
	void badBearerNeverFallsBackToGuest() throws Exception {
		// CLAUDE.md rule 16. Falling back would silently keep a client working as a guest -
		// seeing an empty report list - instead of returning the 401 that tells it to refresh.
		String guestToken = fixtures.sessionToken();
		UUID userId = fixtures.user();
		String badBearer = TestTokens.signingWithUnknownKey("retired-k0")
				.validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + badBearer)
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	@DisplayName("an expired bearer token plus a valid guest token is TOKEN_EXPIRED")
	void expiredBearerNeverFallsBackToGuest() throws Exception {
		String guestToken = fixtures.sessionToken();
		UUID userId = fixtures.user();
		String expired = TestTokens.signingWithCurrentKey().expiredFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	@DisplayName("a valid bearer token wins over a valid guest token")
	void bearerWinsOverGuestToken() throws Exception {
		// Contract 1.3: if both credentials are sent, the bearer token is the one that counts.
		String guestToken = fixtures.sessionToken();
		UUID userId = fixtures.user();
		String bearer = TestTokens.signingWithCurrentKey().validFor(userId, UserRole.USER);

		mockMvc.perform(get(WHOAMI)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
				.header(GuestAuthFilter.HEADER, guestToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerType").value("USER"))
				.andExpect(jsonPath("$.ownerId").value(userId.toString()));
	}

	// --- the user-existence check -------------------------------------------

	@Test
	@DisplayName("a token for a user that no longer exists is AUTH_REQUIRED")
	void deletedUserIsAuthRequired() throws Exception {
		// A deleted account's token still passes every signature and claim check for up to
		// fifteen minutes. The existence check is what makes deletion take effect at once
		// (docs/dataflow.md section 8.1, LLD 6.5).
		UUID neverCreated = UUID.randomUUID();
		String token = TestTokens.signingWithCurrentKey().validFor(neverCreated, UserRole.USER);

		mockMvc.perform(get(WHOAMI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
	}

	// --- public routes -------------------------------------------------------

	@Test
	@DisplayName("public routes stay reachable with no credential")
	void publicRoutesNeedNoCredential() throws Exception {
		mockMvc.perform(get("/api/samples")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	private List<Class<?>> filterClasses() {
		FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
		List<SecurityFilterChain> chains = proxy.getFilterChains();
		assertThat(chains).as("exactly one filter chain").hasSize(1);

		List<Class<?>> classes = new java.util.ArrayList<>();
		for (jakarta.servlet.Filter filter : chains.get(0).getFilters()) {
			classes.add(filter.getClass());
		}
		return classes;
	}

	private static int indexOf(List<Class<?>> filters, Class<?> type) {
		for (int i = 0; i < filters.size(); i++) {
			if (type.isAssignableFrom(filters.get(i))) {
				return i;
			}
		}
		return -1;
	}
}
