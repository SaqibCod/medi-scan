package dev.saq.mediscan.user;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.CurrentOwner;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.session.OwnerRef;

/**
 * {@code GET} and {@code DELETE /api/me} (contract sections 2.6 and 2.7).
 *
 * <p>Both are bearer-only, enforced in the filter chain by {@code hasAnyRole('USER','ADMIN')}
 * - a guest gets {@code 401 AUTH_REQUIRED} and never reaches these methods.
 *
 * <p>Takes a {@code @CurrentOwner OwnerRef} like every other owner-scoped handler, so the
 * user-existence check has already run by the time either method is entered. That is why
 * neither has to consider the possibility of a token for a deleted account.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

	private final UserService users;
	private final ReportRepository reports;
	private final Clock clock;

	public MeController(UserService users, ReportRepository reports, Clock clock) {
		this.users = users;
		this.reports = reports;
		this.clock = clock;
	}

	/** The signed-in user, with how many live reports they own. */
	@GetMapping
	public MeResponse me(@CurrentOwner OwnerRef owner) {
		UUID userId = requireUser(owner);

		UserEntity user = users.find(userId).orElseThrow(MeController::accountGone);
		long reportCount = reports.countOwnedByUser(userId, Instant.now(clock));

		return new MeResponse(user.getId(), user.getDisplayName(), user.getRole(),
				user.getCreatedAt(), reportCount);
	}

	/**
	 * Deletes the account and everything linked to it.
	 *
	 * <p>204 with no body. One statement does it: {@code ON DELETE CASCADE} from
	 * {@code app_user} removes the reports, and from each report its text, biomarkers and
	 * summary, plus every refresh token (contract 2.7).
	 *
	 * <p>No confirmation step and no soft delete. "Delete my account" that leaves the data in
	 * place would not be account deletion, and the client is responsible for asking the user
	 * first.
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentOwner OwnerRef owner) {
		users.delete(requireUser(owner));
	}

	/**
	 * The owner must be a user here.
	 *
	 * <p>Unreachable through the API - the route requires a user role - but written as a
	 * switch over the sealed type so that a change to the route rules cannot silently turn a
	 * guest into a user id.
	 */
	private static UUID requireUser(OwnerRef owner) {
		return switch (owner) {
			case OwnerRef.User user -> user.id();
			case OwnerRef.Guest ignored -> throw new ApiException(ErrorCode.AUTH_REQUIRED,
					"Sign in to use this endpoint.");
		};
	}

	private static ApiException accountGone() {
		return new ApiException(ErrorCode.AUTH_REQUIRED,
				"This account no longer exists. Sign in again.");
	}

	/**
	 * {@code GET /api/me}'s body (contract 2.6).
	 *
	 * @param reportCount live reports this user owns, so the UI can show a count without
	 *     fetching the list
	 */
	public record MeResponse(UUID id, String displayName, UserRole role, Instant createdAt,
			long reportCount) {

		/** Never prints {@link #displayName()}: it is a real person's name (LLD 12). */
		@Override
		public String toString() {
			return "MeResponse[id=" + id + ", role=" + role + ", reportCount=" + reportCount
					+ ", displayName=***]";
		}
	}
}
