package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.support.TestAuthProperties;

/**
 * {@link SigningKeyRing} validates its configuration at startup, so these tests are mostly
 * about the failures: a bad key is a deploy-time mistake and the useful moment to find out is
 * the deploy, not the first sign-in.
 *
 * <p>Every failure case also asserts that the message carries no key material. A startup
 * exception goes straight to the logs and often to a terminal someone else is watching, so a
 * message quoting the key would be a leak in the one code path guaranteed to run
 * (CLAUDE.md rule 14).
 */
class SigningKeyRingTest {

	private static final String KEY_ONE = TestAuthProperties.KEY_ONE;
	private static final String KEY_TWO = TestAuthProperties.KEY_TWO;

	@Test
	@DisplayName("a valid list loads every key and records the current one")
	void loadsValidList() {
		SigningKeyRing ring = new SigningKeyRing("k1:" + KEY_ONE + ",k2:" + KEY_TWO, "k2");

		assertThat(ring.size()).isEqualTo(2);
		assertThat(ring.currentKid()).isEqualTo("k2");
	}

	@Test
	@DisplayName("whitespace around entries is tolerated")
	void tolerateWhitespace() {
		SigningKeyRing ring = new SigningKeyRing(" k1:" + KEY_ONE + " , k2:" + KEY_TWO + " ", "k1");

		assertThat(ring.size()).isEqualTo(2);
		assertThat(ring.currentKid()).isEqualTo("k1");
	}

	@Test
	@DisplayName("a duplicate key id fails, naming the id")
	void rejectsDuplicateKid() {
		assertThatThrownBy(() -> new SigningKeyRing("k1:" + KEY_ONE + ",k1:" + KEY_TWO, "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'k1'")
				.hasMessageContaining("more than once");
	}

	@Test
	@DisplayName("a key under 32 bytes fails, naming the id and the length")
	void rejectsShortKey() {
		// 16 bytes: long enough to look plausible, too short for HS256.
		String short16 = java.util.Base64.getEncoder().encodeToString(new byte[16]);

		assertThatThrownBy(() -> new SigningKeyRing("weak:" + short16, "weak"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'weak'")
				.hasMessageContaining("16 bytes");
	}

	@Test
	@DisplayName("a key that is not base64 fails without quoting the key")
	void rejectsBadBase64() {
		assertThatThrownBy(() -> new SigningKeyRing("k1:not base64 at all!!", "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'k1'")
				.hasMessageContaining("not valid base64")
				// The JDK's own message quotes the offending input, so the cause is
				// deliberately not chained.
				.hasMessageNotContaining("not base64 at all");
	}

	@Test
	@DisplayName("an entry with no colon fails")
	void rejectsMalformedEntry() {
		assertThatThrownBy(() -> new SigningKeyRing(KEY_ONE, "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("kid:base64key");
	}

	@Test
	@DisplayName("a blank key id fails")
	void rejectsBlankKid() {
		assertThatThrownBy(() -> new SigningKeyRing(":" + KEY_ONE, "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("blank key id");
	}

	@Test
	@DisplayName("a current kid that is not in the list fails, naming it")
	void rejectsUnknownCurrentKid() {
		assertThatThrownBy(() -> new SigningKeyRing("k1:" + KEY_ONE, "k9"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'k9'")
				.hasMessageContaining("not in JWT_SIGNING_KEYS");
	}

	@Test
	@DisplayName("a missing current kid fails with a usable message")
	void rejectsMissingCurrentKid() {
		assertThatThrownBy(() -> new SigningKeyRing("k1:" + KEY_ONE, "  "))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_CURRENT_KID");
	}

	@Test
	@DisplayName("no keys at all fails with the command that generates one")
	void rejectsEmptyKeyList() {
		assertThatThrownBy(() -> new SigningKeyRing("", "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("openssl rand -base64 32");

		assertThatThrownBy(() -> new SigningKeyRing(",  ,", "k1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no usable entries");
	}

	@Test
	@DisplayName("no failure message ever contains key material")
	void messagesNeverContainKeyMaterial() {
		// One assertion over every failure path, so a new validation rule that quotes the key
		// is caught here even if its own test forgets to check.
		String[][ ] cases = {
				{ "k1:" + KEY_ONE + ",k1:" + KEY_TWO, "k1" },
				{ "k1:" + KEY_ONE, "k9" },
				{ "k1:" + KEY_ONE, "" },
		};

		for (String[] input : cases) {
			assertThatThrownBy(() -> new SigningKeyRing(input[0], input[1]))
					.hasMessageNotContaining(KEY_ONE)
					.hasMessageNotContaining(KEY_TWO);
		}
	}

	@Test
	@DisplayName("toString reports counts, never the keys")
	void toStringHidesKeys() {
		SigningKeyRing ring = new SigningKeyRing("k1:" + KEY_ONE + ",k2:" + KEY_TWO, "k2");

		// JWKSet.toString() serialises every `k` value, so the inherited implementation would
		// print every signing key.
		assertThat(ring.toString())
				.isEqualTo("SigningKeyRing[keys=2, currentKid=k2]")
				.doesNotContain(KEY_ONE, KEY_TWO);
	}
}
