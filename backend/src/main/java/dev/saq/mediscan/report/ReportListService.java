package dev.saq.mediscan.report;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ProblemDetails;
import dev.saq.mediscan.config.ValidationException;
import dev.saq.mediscan.session.OwnerRef;

/**
 * {@code GET /api/reports} (contract section 4.5, LLD 5).
 *
 * <p>Two queries regardless of how many reports are on the page: the page itself, and one
 * grouped query for the flag counts of every {@code DONE} report on it (LLD 9, test 10).
 *
 * <p>{@code page} and {@code size} are validated here rather than with {@code @Min}/{@code @Max}
 * on the controller parameter, because the bound on {@code size} comes from configuration
 * ({@link MediScanProperties.History}), which an annotation cannot express
 * ({@link ValidationException}'s own javadoc). Invalid values are rejected, never clamped
 * (LLD D6).
 */
@Service
public class ReportListService {

	private final ReportListQuery query;
	private final MediScanProperties.History limits;
	private final Clock clock;

	public ReportListService(ReportListQuery query, MediScanProperties properties, Clock clock) {
		this.query = query;
		this.limits = properties.history();
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<ReportListItem> list(OwnerRef owner, int page, Integer size, ReportStatus status) {
		int pageSize = resolveSize(size);
		validatePage(page);

		Instant now = Instant.now(clock);
		long totalItems = query.count(owner, status, now);
		List<ReportListQuery.Row> rows = query.page(owner, status, now, pageSize, page * pageSize);

		List<UUID> doneIds = rows.stream()
				.filter(row -> row.status() == ReportStatus.DONE)
				.map(ReportListQuery.Row::id)
				.toList();
		Map<UUID, ReportResponse.Counts> counts = query.countsFor(doneIds);

		List<ReportListItem> items = rows.stream().map(row -> toItem(row, counts)).toList();
		return PageResponse.of(items, page, pageSize, totalItems);
	}

	private static ReportListItem toItem(ReportListQuery.Row row, Map<UUID, ReportResponse.Counts> counts) {
		boolean done = row.status() == ReportStatus.DONE;
		return new ReportListItem(
				row.id(),
				row.status(),
				row.sourceType(),
				row.createdAt(),
				row.expiresAt(),
				done ? row.collectedOn() : null,
				done ? counts.get(row.id()) : null,
				row.status() == ReportStatus.FAILED ? row.errorCode() : null);
	}

	private int resolveSize(Integer size) {
		if (size == null) {
			return limits.defaultSize();
		}
		if (size < 1 || size > limits.maxSize()) {
			throw invalid("size", "must be 1 to " + limits.maxSize());
		}
		return size;
	}

	private static void validatePage(int page) {
		if (page < 0) {
			throw invalid("page", "must be 0 or more");
		}
	}

	private static ValidationException invalid(String field, String message) {
		return new ValidationException("One or more fields are invalid.",
				List.of(new ProblemDetails.FieldError(field, message)));
	}
}
