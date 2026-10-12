package dev.saq.mediscan.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.saq.mediscan.support.TestAuthProperties;

/**
 * {@link AdminSubjects} is the whole admin-granting mechanism (CLAUDE.md rule 17), so its
 * parsing is tested for the two directions that matter: a listed subject gets {@code ADMIN},
 * and anything else - including the edge cases a hand-edited {@code .env} produces - does not.
 */
class AdminSubjectsTest {

	private static final String ADMIN_SUB = "116947382910473829104";
	private static final String OTHER_SUB = "209384710293847102938";

	@Test
	@DisplayName("a listed subject gets ADMIN and everyone else gets USER")
	void grantsAdminOnlyToListedSubjects() {
		AdminSubjects subjects = subjects(ADMIN_SUB);

		assertThat(subjects.roleFor(ADMIN_SUB)).isEqualTo(UserRole.ADMIN);
		assertThat(subjects.roleFor(OTHER_SUB)).isEqualTo(UserRole.USER);
	}

	@Test
	@DisplayName("whitespace around entries is ignored")
	void trimsEntries() {
		AdminSubjects subjects = subjects("  " + ADMIN_SUB + " ,\t" + OTHER_SUB + "  ");

		assertThat(subjects.roleFor(ADMIN_SUB)).isEqualTo(UserRole.ADMIN);
		assertThat(subjects.roleFor(OTHER_SUB)).isEqualTo(UserRole.ADMIN);
		assertThat(subjects.size()).isEqualTo(2);
	}

	@Test
	@DisplayName("empty entries from a stray comma are dropped, not treated as a subject")
	void dropsEmptyEntries() {
		// The failure this guards against: keeping "" as an entry would make roleFor("")
		// return ADMIN, and a token with a blank sub would then be an admin token.
		AdminSubjects subjects = subjects("," + ADMIN_SUB + ",,  ,");

		assertThat(subjects.size()).isEqualTo(1);
		assertThat(subjects.roleFor("")).isEqualTo(UserRole.USER);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", ",", " , " })
	@DisplayName("no configured admins means nobody is an admin")
	void noAdminsConfigured(String raw) {
		AdminSubjects subjects = subjects(raw);

		assertThat(subjects.size()).isZero();
		assertThat(subjects.roleFor(ADMIN_SUB)).isEqualTo(UserRole.USER);
	}

	@Test
	@DisplayName("a null subject is never an admin")
	void nullSubjectIsNotAdmin() {
		assertThat(subjects(ADMIN_SUB).roleFor(null)).isEqualTo(UserRole.USER);
	}

	@Test
	@DisplayName("toString reports a count, never the subjects themselves")
	void toStringHidesSubjects() {
		// A Google sub identifies a person and is on the never-logged list (LLD 12).
		assertThat(subjects(ADMIN_SUB).toString())
				.doesNotContain(ADMIN_SUB)
				.isEqualTo("AdminSubjects[count=1]");
	}

	private static AdminSubjects subjects(String raw) {
		return new AdminSubjects(TestAuthProperties.withAdminSubjects(raw));
	}
}
