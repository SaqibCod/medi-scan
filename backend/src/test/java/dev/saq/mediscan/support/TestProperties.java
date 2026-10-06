package dev.saq.mediscan.support;

import java.time.Duration;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * {@link MediScanProperties} for unit tests that construct a collaborator directly.
 *
 * <p>{@code MediScanProperties} is one record with nine nested records, so building it inline
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
				new MediScanProperties.RateLimit(1000, 1000),
				new MediScanProperties.Upload(10_485_760L, 50_000, 20, null, Duration.ofMinutes(10)),
				new MediScanProperties.Jobs(2, 20, Duration.ofSeconds(10)),
				new MediScanProperties.Extract(50, 100, 50),
				new MediScanProperties.Llm("fake", "", "fake-model", Duration.ofSeconds(5),
						1, 2, 500, 300, 1500, 20),
				new MediScanProperties.Mask(false, "classpath:opennlp/en-ner-person.bin", 0.7, 3),
				new MediScanProperties.Retention(900_000L, 90));
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

	public static MediScanProperties withOpenNlp(boolean enabled, double minProbability) {
		MediScanProperties base = defaults();
		MediScanProperties.Mask mask = base.mask();
		return copyWith(base, base.upload(), base.extract(),
				new MediScanProperties.Mask(enabled, mask.opennlpModel(), minProbability,
						mask.maxIntegrityPasses()));
	}

	private static MediScanProperties copyWith(MediScanProperties base,
			MediScanProperties.Upload upload,
			MediScanProperties.Extract extract,
			MediScanProperties.Mask mask) {

		return new MediScanProperties(base.allowedOrigin(), base.session(), base.ratelimit(),
				upload, base.jobs(), extract, base.llm(), mask, base.retention());
	}
}
