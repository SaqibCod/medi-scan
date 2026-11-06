package dev.saq.mediscan.auth;

/**
 * Everything Medi-Scan keeps from a verified Google ID token.
 *
 * <p>Two fields, and that is the point. The token also carries an email address, a verified
 * flag, a profile photo URL, a locale and a hosted-domain claim; all of them are read past and
 * dropped, because storing them would be storing more about a user than the app needs
 * (CLAUDE.md rule 13). The token itself is discarded once verified.
 *
 * @param sub Google's stable subject id. The account key, and the only thing matched against
 *     {@code ADMIN_GOOGLE_SUBS}. Never logged
 * @param displayName the {@code name} claim, already trimmed, capped and defaulted. Never
 *     logged
 */
public record GoogleIdentity(String sub, String displayName) {

	/** Google's {@code name} claim is free text; 100 characters is more than a header shows. */
	static final int MAX_DISPLAY_NAME = 100;

	/** What to store when Google sent no usable name. Never blank, because the UI shows it. */
	static final String FALLBACK_DISPLAY_NAME = "User";

	/**
	 * Builds an identity from a verified token's raw claims, normalising the name.
	 *
	 * <p>Normalisation lives here rather than in the user layer so there is exactly one place
	 * that decides what a display name may be. The {@code name} claim is controlled by the
	 * account holder, so it is treated as untrusted input: trimmed, capped so it cannot bloat
	 * a row, defaulted when blank, and never interpolated anywhere but a JSON response the
	 * client renders as text.
	 */
	public static GoogleIdentity of(String sub, String rawName) {
		return new GoogleIdentity(sub, normalizeDisplayName(rawName));
	}

	/** Trims, caps at {@value #MAX_DISPLAY_NAME}, falls back to {@value #FALLBACK_DISPLAY_NAME}. */
	static String normalizeDisplayName(String raw) {
		if (raw == null || raw.isBlank()) {
			return FALLBACK_DISPLAY_NAME;
		}
		String trimmed = raw.trim();
		return trimmed.length() > MAX_DISPLAY_NAME
				? trimmed.substring(0, MAX_DISPLAY_NAME)
				: trimmed;
	}

	/**
	 * Never prints either field.
	 *
	 * <p>The {@code sub} identifies a Google account and the display name is a real person's
	 * name; both are on the never-logged list (LLD 12). A record's generated {@code toString}
	 * would print both, and this type exists at exactly the point where a stray
	 * {@code log.debug(identity)} would be easy to write.
	 */
	@Override
	public String toString() {
		return "GoogleIdentity[sub=***, displayName=***]";
	}
}
