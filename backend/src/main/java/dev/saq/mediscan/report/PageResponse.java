package dev.saq.mediscan.report;

import java.util.List;

/**
 * One page of a list endpoint - the generic {@code Page<T>} from contract section 9.1.
 *
 * <p>{@code totalPages} is {@code 0} when there are no items, and a {@code page} past the last
 * one still returns {@code 200} with an empty {@code items} list (contract section 4.5).
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
		int totalPages = (int) Math.ceil((double) totalItems / size);
		return new PageResponse<>(items, page, size, totalItems, totalPages);
	}
}
