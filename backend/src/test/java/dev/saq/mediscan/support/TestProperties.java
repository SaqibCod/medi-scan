package dev.saq.mediscan.support;

import java.time.Duration;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * {@link MediScanProperties} for unit tests that construct a collaborator directly.
 *
 * <p>{@code MediScanProperties} is one record with ten nested records, so building it inline
 * buries the one value a test cares about in thirty it does not. These factories start from
 * production-like defaults and let a test override only what it is actually testing.
 */
public final class TestProperties {

	private TestProperties() {
	}

	/** Defaults matching {@code application.yml}, with the fake LLM provider. */
	public static MediScanProperties defaults() {
		return new MediScanProperties(
				"http://localhost:3000",
				new MediScanProperties.Session(24),
				new MediScanProperties.RateLimit(1000, 1000, 1000),
				new MediScanProperties.Upload(10_485_760L, 50_000, 20, null, Duration.ofMinutes(10)),
				new MediScanProperties.Jobs(2, 20, Duration.ofSeconds(10)),
				new MediScanProperties.Extract(50, 100, 50),
				defaultOcr(),
				new MediScanProperties.Llm("fake", "", "fake-model", Duration.ofSeconds(5),
						1, 2, 500, 300, 1500, 20),
				new MediScanProperties.Mask(false, "classpath:opennlp/en-ner-person.bin", 0.7, 3),
				new MediScanProperties.Retention(900_000L, 90, 30, 180),
				new MediScanProperties.History(20, 50),
				new MediScanProperties.Trends(100, 2, 200),
				new MediScanProperties.Admin(14, 90));
	}

	/** The OCR defaults from {@code application.yml}. */
	public static MediScanProperties.Ocr defaultOcr() {
		return new MediScanProperties.Ocr(true, "tesseract", "", 4, 1,
				Duration.ofSeconds(45), Duration.ofSeconds(150), 10,
				40_000_000L, 67_108_864L, 1500, 4000, 2_097_152, 30, 80);
	}

	public static MediScanProperties withExtract(int minCharsPerPage, int minTotalChars, int maxPages) {
		MediScanProperties base = defaults();
		return copyWith(base, base.upload(),
				new MediScanProperties.Extract(minCharsPerPage, minTotalChars, maxPages),
				base.mask());
	}

	public static MediScanProperties withMaxTextChars(int maxTextChars) {
		MediScanProperties base = defaults();
		MediScanProperties.Upload upload = base.upload();
		return copyWith(base,
				new MediScanProperties.Upload(upload.maxBytes(), maxTextChars, upload.minTextChars(),
						upload.tempDir(), upload.tempFileMaxAge()),
				base.extract(), base.mask());
	}

	public static MediScanProperties withUpload(long maxBytes, int maxTextChars, int minTextChars,
			String tempDir) {

		MediScanProperties base = defaults();
		return copyWith(base,
				new MediScanProperties.Upload(maxBytes, maxTextChars, minTextChars, tempDir,
						base.upload().tempFileMaxAge()),
				base.extract(), base.mask());
	}

	public static MediScanProperties withTempDir(String tempDir, Duration tempFileMaxAge) {
		MediScanProperties base = defaults();
		MediScanProperties.Upload upload = base.upload();
		return copyWith(base,
				new MediScanProperties.Upload(upload.maxBytes(), upload.maxTextChars(),
						upload.minTextChars(), tempDir, tempFileMaxAge),
				base.extract(), base.mask());
	}

	public static MediScanProperties withMaxRows(int maxRows) {
		MediScanProperties base = defaults();
		MediScanProperties.Llm llm = base.llm();
		return withLlm(base, new MediScanProperties.Llm(llm.provider(), llm.apiKey(), llm.model(),
				llm.timeout(), llm.maxInvalidRetries(), llm.maxTransientRetries(),
				llm.dailyCap(), maxRows, llm.maxSummaryChars(), llm.maxHighlights()));
	}

	public static MediScanProperties withLlmProvider(String provider, String apiKey) {
		MediScanProperties base = defaults();
		MediScanProperties.Llm llm = base.llm();
		return withLlm(base, new MediScanProperties.Llm(provider, apiKey, llm.model(), llm.timeout(),
				llm.maxInvalidRetries(), llm.maxTransientRetries(), llm.dailyCap(),
				llm.maxRows(), llm.maxSummaryChars(), llm.maxHighlights()));
	}

	public static MediScanProperties withJobs(int workers, int queueCapacity) {
		MediScanProperties base = defaults();
		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(),
				new MediScanProperties.Jobs(workers, queueCapacity, base.jobs().shutdownGrace()),
				base.extract(), base.ocr(), base.llm(), base.mask(), base.retention(),
				base.history(), base.trends(), base.admin());
	}

	public static MediScanProperties withHistory(int defaultSize, int maxSize) {
		MediScanProperties base = defaults();
		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(), base.jobs(), base.extract(), base.ocr(), base.llm(), base.mask(),
				base.retention(), new MediScanProperties.History(defaultSize, maxSize),
				base.trends(), base.admin());
	}

	public static MediScanProperties withTrends(int pointCap, int markerMinReports, int markerCap) {
		MediScanProperties base = defaults();
		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(), base.jobs(), base.extract(), base.ocr(), base.llm(), base.mask(),
				base.retention(), base.history(),
				new MediScanProperties.Trends(pointCap, markerMinReports, markerCap),
				base.admin());
	}

	public static MediScanProperties withAdmin(int defaultDays, int maxDays) {
		MediScanProperties base = defaults();
		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(), base.jobs(), base.extract(), base.ocr(), base.llm(), base.mask(),
				base.retention(), base.history(), base.trends(),
				new MediScanProperties.Admin(defaultDays, maxDays));
	}

	public static MediScanProperties withOpenNlp(boolean enabled, double minProbability) {
		MediScanProperties base = defaults();
		MediScanProperties.Mask mask = base.mask();
		return copyWith(base, base.upload(), base.extract(),
				new MediScanProperties.Mask(enabled, mask.opennlpModel(), minProbability,
						mask.maxIntegrityPasses()));
	}

	public static MediScanProperties withOpenNlpModel(String modelLocation) {
		MediScanProperties base = defaults();
		MediScanProperties.Mask mask = base.mask();
		return copyWith(base, base.upload(), base.extract(),
				new MediScanProperties.Mask(true, modelLocation, mask.opennlpMinProbability(),
						mask.maxIntegrityPasses()));
	}

	/** Swaps in a whole OCR block, for the tests that drive the engine and the preparer. */
	public static MediScanProperties withOcr(MediScanProperties.Ocr ocr) {
		MediScanProperties base = defaults();
		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(), base.jobs(), base.extract(), ocr, base.llm(), base.mask(),
				base.retention(), base.history(), base.trends(), base.admin());
	}

	public static MediScanProperties withOcrEnabled(boolean enabled) {
		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(enabled, ocr.command(), ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				ocr.maxOutputBytes(), ocr.minPageConfidence(), ocr.minValueConfidence()));
	}

	public static MediScanProperties withOcrCommand(String command) {
		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(ocr.enabled(), command, ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				ocr.maxOutputBytes(), ocr.minPageConfidence(), ocr.minValueConfidence()));
	}

	public static MediScanProperties withOcrOutputCap(int maxOutputBytes) {
		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(ocr.enabled(), ocr.command(), ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				maxOutputBytes, ocr.minPageConfidence(), ocr.minValueConfidence()));
	}

	/** Image limits: the pixel cap, the decoded-byte budget, and the long-edge band. */
	public static MediScanProperties withOcrImageLimits(long maxPixels, long maxDecodedBytes,
			int minLongEdge, int maxLongEdge) {

		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(ocr.enabled(), ocr.command(), ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				maxPixels, maxDecodedBytes, minLongEdge, maxLongEdge,
				ocr.maxOutputBytes(), ocr.minPageConfidence(), ocr.minValueConfidence()));
	}

	/** The two confidence thresholds, which the evaluation tunes. */
	public static MediScanProperties withOcrConfidence(double minPageConfidence,
			double minValueConfidence) {

		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(ocr.enabled(), ocr.command(), ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), ocr.pageTimeout(), ocr.totalTimeout(), ocr.maxPages(),
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				ocr.maxOutputBytes(), minPageConfidence, minValueConfidence));
	}

	/** The OCR page budget and the two timeouts. */
	public static MediScanProperties withOcrLimits(int maxPages, Duration pageTimeout,
			Duration totalTimeout) {

		MediScanProperties.Ocr ocr = defaultOcr();
		return withOcr(new MediScanProperties.Ocr(ocr.enabled(), ocr.command(), ocr.tessdataDir(),
				ocr.psm(), ocr.threads(), pageTimeout, totalTimeout, maxPages,
				ocr.maxPixels(), ocr.maxDecodedBytes(), ocr.minLongEdge(), ocr.maxLongEdge(),
				ocr.maxOutputBytes(), ocr.minPageConfidence(), ocr.minValueConfidence()));
	}

	private static MediScanProperties withLlm(MediScanProperties base,
			MediScanProperties.Llm llm) {

		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				base.upload(), base.jobs(), base.extract(), base.ocr(), llm, base.mask(),
				base.retention(), base.history(), base.trends(), base.admin());
	}

	private static MediScanProperties copyWith(MediScanProperties base,
			MediScanProperties.Upload upload,
			MediScanProperties.Extract extract,
			MediScanProperties.Mask mask) {

		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				upload, base.jobs(), extract, base.ocr(), base.llm(), mask, base.retention(),
				base.history(), base.trends(), base.admin());
	}
}
