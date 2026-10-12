package dev.saq.mediscan.trends;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.analysis.BiomarkerCatalog;
import dev.saq.mediscan.analysis.TestNameNormalizer;
import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ProblemDetails;
import dev.saq.mediscan.config.ValidationException;
import dev.saq.mediscan.session.OwnerRef;

/**
 * {@code GET /api/trends} and {@code GET /api/trends/markers} (contract section 5, LLD 6).
 *
 * <p>Matching reuses {@link TestNameNormalizer} - the exact function Phase 2 uses to write
 * {@code biomarker.test_name_norm} - rather than a second implementation of the same rules
 * ({@code backend/CLAUDE.md}: "No second normalizer").
 */
@Service
public class TrendsService {

	private static final Logger log = LoggerFactory.getLogger(TrendsService.class);

	private final TrendsQuery query;
	private final MediScanProperties.Trends limits;
	private final Clock clock;

	/** Slug to display name, built once from the catalog's immutable entries. */
	private final Map<String, String> displayNamesBySlug;

	public TrendsService(TrendsQuery query, BiomarkerCatalog catalog, MediScanProperties properties,
			Clock clock) {
		this.query = query;
		this.limits = properties.trends();
		this.clock = clock;

		Map<String, String> bySlug = new LinkedHashMap<>();
		for (BiomarkerCatalog.Entry entry : catalog.entries()) {
			bySlug.put(entry.slug(), entry.displayName());
		}
		this.displayNamesBySlug = Map.copyOf(bySlug);
	}

	@Transactional(readOnly = true)
	public List<TrendMarker> markers(OwnerRef owner) {
		UUID userId = requireUser(owner);
		Instant now = Instant.now(clock);

		List<TrendsQuery.MarkerRow> rows = query.markers(userId, now, limits.markerMinReports());

		List<TrendMarker> items = new ArrayList<>();
		for (TrendsQuery.MarkerRow row : rows) {
			String displayName = row.biomarkerSlug() != null
					? displayNamesBySlug.getOrDefault(row.biomarkerSlug(), row.testName())
					: row.testName();
			items.add(new TrendMarker(row.markerKey(), displayName, row.reportCount()));
		}

		items.sort(Comparator
				.comparingLong(TrendMarker::reportCount).reversed()
				.thenComparing(TrendMarker::displayName));

		List<TrendMarker> capped = items.size() > limits.markerCap()
				? items.subList(0, limits.markerCap())
				: items;
		log.info("trend markers listed count={}", capped.size());
		return capped;
	}

	@Transactional(readOnly = true)
	public TrendResponse trend(OwnerRef owner, String markerParam) {
		UUID userId = requireUser(owner);
		String marker = validateMarker(markerParam);
		String normalizedMarker = TestNameNormalizer.normalize(marker);

		Instant now = Instant.now(clock);
		List<TrendsQuery.PointRow> rows = query.points(userId, marker, normalizedMarker, now);

		if (rows.isEmpty()) {
			return new TrendResponse(normalizedMarker, marker, List.of());
		}

		boolean slugMatched = rows.stream().anyMatch(row -> marker.equals(row.biomarkerSlug()));
		String responseMarker = slugMatched ? marker : normalizedMarker;
		String displayName = displayName(rows, marker, slugMatched);

		List<TrendResponse.Series> series = toSeries(rows);
		log.info("trend computed seriesCount={} pointCount={}", series.size(), rows.size());
		return new TrendResponse(responseMarker, displayName, series);
	}

	private String displayName(List<TrendsQuery.PointRow> rows, String marker, boolean slugMatched) {
		if (slugMatched && displayNamesBySlug.containsKey(marker)) {
			return displayNamesBySlug.get(marker);
		}
		// The most recent matching row's printed name - rows are already sorted oldest first,
		// so the last one is the most recent.
		return rows.get(rows.size() - 1).testName();
	}

	private List<TrendResponse.Series> toSeries(List<TrendsQuery.PointRow> rows) {
		Map<String, List<TrendsQuery.PointRow>> byUnit = new LinkedHashMap<>();
		for (TrendsQuery.PointRow row : rows) {
			byUnit.computeIfAbsent(row.unit(), key -> new ArrayList<>()).add(row);
		}

		List<TrendResponse.Series> series = new ArrayList<>();
		for (Map.Entry<String, List<TrendsQuery.PointRow>> entry : byUnit.entrySet()) {
			List<TrendsQuery.PointRow> points = entry.getValue();
			// Still sorted oldest first because the source query already is, and grouping
			// preserves encounter order (LLD D2).
			if (points.size() > limits.pointCap()) {
				points = points.subList(points.size() - limits.pointCap(), points.size());
			}
			series.add(new TrendResponse.Series(entry.getKey(), points.stream().map(this::toPoint).toList()));
		}

		// Most points first, then unit alphabetical with a null unit last (LLD 6.2).
		series.sort(Comparator
				.<TrendResponse.Series>comparingInt(s -> s.points().size()).reversed()
				.thenComparing(s -> s.unit() == null ? 1 : 0)
				.thenComparing(s -> s.unit() == null ? "" : s.unit()));

		return series;
	}

	private TrendResponse.TrendPoint toPoint(TrendsQuery.PointRow row) {
		boolean collected = row.collectedOn() != null;
		return new TrendResponse.TrendPoint(
				row.reportId(),
				collected ? row.collectedOn() : row.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
				collected ? DateSource.COLLECTED : DateSource.UPLOADED,
				row.numericValue(),
				row.refLow(),
				row.refHigh(),
				row.flag());
	}

	private String validateMarker(String markerParam) {
		if (markerParam == null || markerParam.isBlank()) {
			throw invalid("marker", "is required");
		}
		String trimmed = markerParam.trim();
		if (trimmed.length() > 100) {
			throw invalid("marker", "must be 100 characters or fewer");
		}
		return trimmed;
	}

	/**
	 * The owner must be a user here - unreachable through the API, since
	 * {@code /api/trends/**} requires {@code hasAnyRole('USER','ADMIN')}, but written as a
	 * switch over the sealed type so a change to the route rules cannot silently turn a guest
	 * into a user id, matching {@code MeController}'s own {@code requireUser}.
	 */
	private static UUID requireUser(OwnerRef owner) {
		return switch (owner) {
			case OwnerRef.User user -> user.id();
			case OwnerRef.Guest ignored -> throw new ApiException(ErrorCode.AUTH_REQUIRED,
					"Sign in to use this endpoint.");
		};
	}

	private static ValidationException invalid(String field, String message) {
		return new ValidationException("One or more fields are invalid.",
				List.of(new ProblemDetails.FieldError(field, message)));
	}
}
