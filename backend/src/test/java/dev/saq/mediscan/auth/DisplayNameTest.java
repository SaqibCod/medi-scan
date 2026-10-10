package dev.saq.mediscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link GoogleIdentity#normalizeDisplayName} handles Google's {@code name} claim, which is free
 * text the account holder controls. Table-driven because the interesting cases are all at the
 * edges: nothing, whitespace, and something far longer than a header can show.
 */
class DisplayNameTest {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t", "\n", "   \t  " })
	@DisplayName("a missing or blank name becomes 'User', never a blank display name")
	void blankBecomesFallback(String raw) {
		// The UI renders this in the header, so an empty string would show as a gap.
		assertThat(GoogleIdentity.normalizeDisplayName(raw)).isEqualTo("User");
	}

	@Test
	@DisplayName("a normal name is kept, trimmed")
	void keepsNormalName() {
		assertThat(GoogleIdentity.normalizeDisplayName("  Priya Raman  ")).isEqualTo("Priya Raman");
	}

	@Test
	@DisplayName("a name longer than 100 characters is cut to 100")
	void capsLongName() {
		String long200 = "x".repeat(200);

		String normalized = GoogleIdentity.normalizeDisplayName(long200);

		assertThat(normalized).hasSize(100);
	}

	@Test
	@DisplayName("a name of exactly 100 characters is kept whole")
	void keepsExactlyAtLimit() {
		String exactly100 = "y".repeat(100);

		assertThat(GoogleIdentity.normalizeDisplayName(exactly100)).isEqualTo(exactly100);
	}

	@Test
	@DisplayName("trimming happens before the length check")
	void trimsBeforeCapping() {
		// Otherwise 100 characters of padding plus a name would be cut to padding alone.
		String padded = " ".repeat(50) + "Sam" + " ".repeat(80);

		assertThat(GoogleIdentity.normalizeDisplayName(padded)).isEqualTo("Sam");
	}
}
