package dev.saq.mediscan.user;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.auth.AuthProperties;

/**
 * The parsed {@code ADMIN_GOOGLE_SUBS} list.
 *
 * <p>This is the whole admin-granting mechanism (CLAUDE.md rule 17). There is no endpoint, no
 * UI, and no database column anyone can write to promote themselves; {@code app_user.role} is
 * only ever a cache of what this set says.
 *
 * <p>Parsing is forgiving about whitespace and empty entries, because the value is typed into
 * an {@code .env} file by hand and a stray trailing comma should not quietly produce an
 * {@code ADMIN} entry that matches the empty {@code sub}.
 */
@Component
public class AdminSubjects {

	private final Set<String> subjects;

	public AdminSubjects(AuthProperties properties) {
		this.subjects = parse(properties.adminSubjects());
	}

	static Set<String> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(raw.split(","))
				.map(entry -> entry.trim())
				.filter(entry -> !entry.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	/** The role this Google subject gets. */
	public UserRole roleFor(String googleSub) {
		return googleSub != null && subjects.contains(googleSub) ? UserRole.ADMIN : UserRole.USER;
	}

	/** How many admins are configured. For the startup log line, which must not name them. */
	public int size() {
		return subjects.size();
	}

	/**
	 * Never prints the subjects.
	 *
	 * <p>A Google {@code sub} identifies a person and is on the never-logged list (LLD 12), so
	 * the count is all this reports.
	 */
	@Override
	public String toString() {
		return "AdminSubjects[count=" + subjects.size() + "]";
	}
}
