package dev.saq.mediscan.analysis;

import java.util.UUID;

import dev.saq.mediscan.extract.JobSource;

/**
 * Builds the {@link Runnable} that runs one report through the pipeline.
 *
 * <p>An interface so the upload layer depends on "something that makes a job" rather than on
 * the pipeline itself. {@code upload} may call {@code analysis}
 * ({@code backend/CLAUDE.md}, "Dependency direction"), and this keeps that one call as narrow
 * as it can be: a report id, a source, and a {@code Runnable} back.
 */
public interface ReportJobFactory {

	/**
	 * A job that processes {@code reportId} from {@code source}.
	 *
	 * <p>The returned job owns the temp file behind {@code source} and must delete it, even on
	 * failure ({@code CLAUDE.md} rule 2).
	 */
	Runnable create(UUID reportId, JobSource source);
}
