package dev.saq.mediscan.upload;

/**
 * One entry in {@code GET /api/samples} (contract section 3).
 *
 * <p>A separate type from {@link Sample} rather than serializing that one: the catalogue
 * entry carries the report text, and this is the public shape. Keeping them apart means no
 * future change to the catalogue can accidentally publish the text through the API.
 */
public record SampleResponse(String id, String title, String description, int markerCount) {

	static SampleResponse from(Sample sample) {
		return new SampleResponse(sample.id(), sample.title(), sample.description(),
				sample.markerCount());
	}
}
