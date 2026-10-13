package dev.saq.mediscan.trends;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.saq.mediscan.report.Flag;

/**
 * {@code GET /api/trends}'s body (contract section 5, LLD 6.2).
 *
 * <p>{@code marker} echoes the slug when the match used one, otherwise the normalized name.
 * Series are grouped by unit exactly as stored - never converted, never merged - and a
 * biomarker with no unit gets its own series with {@code unit: null} (LLD D4).
 *
 * <p>Values and dates are health data, so {@link #toString()} and {@link TrendPoint#toString()}
 * print only counts and ids (LLD 4).
 */
public record TrendResponse(String marker, String displayName, List<Series> series) {

	@Override
	public String toString() {
		return "TrendResponse[marker=" + marker + ", series=" + series.size() + "]";
	}

	/** One chart line: all the points that share a unit. */
	public record Series(String unit, List<TrendPoint> points) {

		@Override
		public String toString() {
			return "Series[unit=" + unit + ", points=" + points.size() + "]";
		}
	}

	/**
	 * One plotted value.
	 *
	 * @param value already validated and re-parsed in code, never taken from the model
	 * @param flag recomputed in code, never requested from or trusted from the model
	 */
	public record TrendPoint(UUID reportId, LocalDate date, DateSource dateSource, double value,
			Double refLow, Double refHigh, Flag flag) {

		@Override
		public String toString() {
			return "TrendPoint[reportId=" + reportId + ", dateSource=" + dateSource + "]";
		}
	}
}
