package medi_scan.backend.session;

import java.time.Instant;

/**
 * Response body of {@code POST /api/sessions} (contract section 2.2).
 *
 * <p>The only time the raw token leaves the server. The client stores it in
 * {@code sessionStorage} and sends it as {@code X-Session-Token}.
 *
 * @param token     32 random bytes, base64url without padding (43 characters)
 * @param expiresAt 24 hours after creation; the session's reports expire with it
 */
public record SessionResponse(String token, Instant expiresAt) {
}
