package dev.saq.mediscan.upload;

/**
 * One bundled synthetic sample report, with its text already loaded.
 *
 * <p>Loaded once at startup rather than read per request: three files of about 1.5 KB each
 * cost nothing to hold, and it keeps file I/O out of the job thread.
 *
 * @param id the slug callers pass as {@code sampleId}
 * @param title button label (contract section 3)
 * @param description one line shown under the label
 * @param markerCount how many lab values the file contains
 * @param text the report text itself
 */
public record Sample(String id, String title, String description, int markerCount, String text) {

	/**
	 * Hides {@link #text()}.
	 *
	 * <p>Sample text is synthetic, so leaking it would harm nobody - but it is still report
	 * text, and it flows through exactly the same masking, extraction and prompt code as a
	 * real upload. If this record printed its text, the canary test could pass on a sample
	 * while the real path leaked, so the rule is applied here too
	 * ({@code backend/CLAUDE.md}: "Records print their fields").
	 */
	@Override
	public String toString() {
		return "Sample[id=" + id + ", markerCount=" + markerCount + ", textLength=" + text.length() + "]";
	}
}
