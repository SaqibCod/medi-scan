package dev.saq.mediscan.trends;

/**
 * One entry in the trend picker, {@code GET /api/trends/markers} (contract section 5).
 *
 * <p>{@code reportCount} is a count only - no values, no dates - so printing it is safe.
 */
public record TrendMarker(String marker, String displayName, long reportCount) {
}
