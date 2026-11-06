package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.extract.ExtractedText;
import dev.saq.mediscan.extract.JobSource;
import dev.saq.mediscan.extract.TextExtractors;
import dev.saq.mediscan.report.TextSource;
import dev.saq.mediscan.support.TestProperties;

/**
 * The OCR parameter sweep ({@code docs/phase-4-lld.md} 10.3).
 *
 * <p>Runs real Tesseract over every rendered variant of a sample of the evaluation cases at each
 * page-segmentation mode, and scores the text it produced against the committed answer keys. No
 * model is involved, so this costs nothing but minutes - which is exactly why the settings can
 * be measured instead of guessed (O10).
 *
 * <p>It is a measurement, not a gate: nothing here fails the build for a poor number, because
 * the point is to produce a table and choose from it. The one thing it does assert is that the
 * harness itself worked - a sweep where every case failed to extract would otherwise print a
 * tidy table of zeroes and look like a result.
 *
 * <p>Run it with {@code ./mvnw test -Pocr-tuning}. Needs {@code tesseract} with {@code eng}.
 */
@Tag("ocr-tuning")
class OcrTuningRun {

	private static final Logger log = LoggerFactory.getLogger(OcrTuningRun.class);

	/** The modes LLD 10.3 names: 3 is fully automatic, 4 a single column, 6 a uniform block. */
	private static final List<Integer> PAGE_SEGMENTATION_MODES = List.of(3, 4, 6);

	/** The thresholds LLD 10.3 names. Applied to one OCR run each, not re-OCR'd. */
	private static final List<Double> VALUE_CONFIDENCE_THRESHOLDS = List.of(70.0, 80.0, 90.0);

	/**
	 * The cases from LLD 10.1, chosen there rather than here: the four clean panels, the three
	 * that stress number reading, and the two that plant the most personal data.
	 *
	 * <p>Ten cases across six variants at three modes is 180 OCR runs, which is the most that
	 * finishes in a coffee break on a laptop. Widening it buys precision this decision does not
	 * need.
	 */
	private static final List<String> CASE_IDS = List.of(
			"lipid-clean-table",
			"cbc-clean-table",
			"cmp-clean-table",
			"thyroid-inline",
			"comparators",
			"number-formats",
			"boundary-values",
			"flags-as-asterisks",
			"pii-names-everywhere",
			"pii-formats");

	@TempDir
	Path tempDir;

	@Test
	void sweep() {
		List<EvalDatasetLoader.Entry> cases = EvalDatasetLoader.loadAll().stream()
				.filter(entry -> CASE_IDS.contains(entry.evalCase().id()))
				.toList();
		assertThat(cases)
				.as("the tuning cases must all exist in eval/cases")
				.hasSize(CASE_IDS.size());

		List<Row> rows = new ArrayList<>();
		Instant startedAt = Instant.now();

		for (int psm : PAGE_SEGMENTATION_MODES) {
			MediScanProperties properties = propertiesFor(psm);

			for (OcrCaseRenderer.Variant variant : OcrCaseRenderer.Variant.values()) {
				Accumulator accumulator = new Accumulator();

				for (EvalDatasetLoader.Entry entry : cases) {
					// Monospaced for every case, and the proportional pass as a separate row
					// below: mixing the two inside one number would hide whichever is worse.
					runOne(entry, variant, OcrCaseRenderer.Typeface.MONOSPACED, properties,
							accumulator);
				}

				rows.add(accumulator.toRow(psm, variant, OcrCaseRenderer.Typeface.MONOSPACED));
				log.info("swept psm={} variant={} typeface=MONOSPACED", psm, variant);
			}

			// The proportional pass, on the clean scan only. It answers "does the font matter?"
			// and that question does not need every variant repeated to answer it.
			Accumulator proportional = new Accumulator();
			for (EvalDatasetLoader.Entry entry : cases) {
				runOne(entry, OcrCaseRenderer.Variant.CLEAN_SCAN,
						OcrCaseRenderer.Typeface.PROPORTIONAL, properties, proportional);
			}
			rows.add(proportional.toRow(psm, OcrCaseRenderer.Variant.CLEAN_SCAN,
					OcrCaseRenderer.Typeface.PROPORTIONAL));
		}

		// The harness worked: if nothing extracted, the table below is a table of zeroes that
		// would read as a finding about OCR rather than a broken test.
		assertThat(rows.stream().mapToInt(Row::pagesScored).sum())
				.as("no case produced any OCR text - check that tesseract is on the PATH")
				.isPositive();

		String report = renderTable(rows, Duration.between(startedAt, Instant.now()));
		log.info("OCR tuning table:\n{}", report);
		write(report);
	}

	/** Renders one case as one variant, extracts it, and folds the score in. */
	private void runOne(EvalDatasetLoader.Entry entry, OcrCaseRenderer.Variant variant,
			OcrCaseRenderer.Typeface typeface, MediScanProperties properties,
			Accumulator accumulator) {

		EvalCase evalCase = entry.evalCase();
		// Seeded from the case id, so a photo is the same photo on every run and in every mode.
		long seed = evalCase.id().hashCode();
		OcrCaseRenderer.Rendered rendered =
				OcrCaseRenderer.render(evalCase.reportText(), variant, typeface, seed);

		Path file = null;
		try {
			file = Files.createTempFile(tempDir, "ocr-case-", suffixFor(rendered.kind()));
			Files.write(file, rendered.bytes());

			Instant startedAt = Instant.now();
			ExtractedText extracted = extract(rendered.kind(), file, properties);
			long millis = Duration.between(startedAt, Instant.now()).toMillis();

			accumulator.add(OcrTextMetrics.score(extracted, entry.expected(), millis),
					extracted.textSource());
		}
		catch (ReportFailure failure) {
			// A case the pipeline refused is a real outcome worth counting, not an error: a
			// variant that is unreadable at one mode and fine at another is precisely what the
			// sweep is looking for.
			accumulator.addFailure(failure.code().name());
			log.info("case failed id={} variant={} code={}", evalCase.id(), variant,
					failure.code());
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not write a rendered case", ex);
		}
		finally {
			deleteQuietly(file);
		}
	}

	private ExtractedText extract(OcrCaseRenderer.Kind kind, Path file,
			MediScanProperties properties) {

		return switch (kind) {
			case PDF -> TextExtractors.pdfWithRealOcr(properties)
					.extract(new JobSource.PdfFile(file));
			case PNG -> TextExtractors.imageWithRealOcr(properties)
					.extract(new JobSource.ImageFile(file, JobSource.ImageKind.PNG));
			case JPEG -> TextExtractors.imageWithRealOcr(properties)
					.extract(new JobSource.ImageFile(file, JobSource.ImageKind.JPEG));
		};
	}

	private static String suffixFor(OcrCaseRenderer.Kind kind) {
		return switch (kind) {
			case PDF -> ".pdf";
			case PNG -> ".png";
			case JPEG -> ".jpg";
		};
	}

	/**
	 * Production defaults with the mode swapped and the budget widened.
	 *
	 * <p>The timeouts are the one thing deliberately not production-like: a sweep of 180 pages
	 * on a laptop sharing a CPU with a browser will occasionally exceed a 45-second page, and a
	 * timeout recorded as a reading failure would corrupt the table. Timing is reported
	 * separately, so nothing is hidden by this.
	 */
	private static MediScanProperties propertiesFor(int psm) {
		MediScanProperties.Ocr base = TestProperties.defaultOcr();
		return TestProperties.withOcr(new MediScanProperties.Ocr(
				true, base.command(), base.tessdataDir(), psm, base.threads(),
				Duration.ofMinutes(2), Duration.ofMinutes(20), base.maxPages(),
				base.maxPixels(), base.maxDecodedBytes(), base.minLongEdge(), base.maxLongEdge(),
				base.maxOutputBytes(), base.minPageConfidence(), base.minValueConfidence()));
	}

	// --- accumulation --------------------------------------------------------

	/** Totals for one (mode, variant, typeface) cell of the table. */
	private static final class Accumulator {

		private int pagesScored;
		private int expectedValues;
		private int recoveredValues;
		private int expectedRanges;
		private int recoveredRanges;
		private int expectedNames;
		private int recoveredNames;
		private int expectedPii;
		private int recoveredPii;
		private long totalMillis;
		private double confidenceSum;
		private final List<Double> recoveredConfidences = new ArrayList<>();
		private final List<Double> missedLineConfidences = new ArrayList<>();
		private final Map<String, Integer> failures = new LinkedHashMap<>();
		private final Map<TextSource, Integer> textSources = new LinkedHashMap<>();

		void add(OcrTextMetrics.PageScore score, TextSource textSource) {
			pagesScored++;
			expectedValues += score.expectedValues();
			recoveredValues += score.recoveredValues();
			expectedRanges += score.expectedRanges();
			recoveredRanges += score.recoveredRanges();
			expectedNames += score.expectedNames();
			recoveredNames += score.recoveredNames();
			expectedPii += score.expectedPii();
			recoveredPii += score.recoveredPii();
			totalMillis += score.ocrMillis();
			confidenceSum += score.meanConfidence();
			recoveredConfidences.addAll(score.recoveredConfidences());
			missedLineConfidences.addAll(score.missedLineConfidences());
			textSources.merge(textSource, 1, Integer::sum);
		}

		void addFailure(String code) {
			failures.merge(code, 1, Integer::sum);
		}

		Row toRow(int psm, OcrCaseRenderer.Variant variant, OcrCaseRenderer.Typeface typeface) {
			Map<Double, Double> unclear = new LinkedHashMap<>();
			Map<Double, Double> risky = new LinkedHashMap<>();
			for (double threshold : VALUE_CONFIDENCE_THRESHOLDS) {
				unclear.put(threshold, OcrTextMetrics.unclearRate(recoveredConfidences, threshold));
				risky.put(threshold, OcrTextMetrics.riskyRate(missedLineConfidences, threshold));
			}

			return new Row(psm, variant, typeface, pagesScored,
					rate(recoveredValues, expectedValues),
					rate(recoveredRanges, expectedRanges),
					rate(recoveredNames, expectedNames),
					rate(recoveredPii, expectedPii),
					pagesScored == 0 ? 0 : confidenceSum / pagesScored,
					pagesScored == 0 ? 0 : totalMillis / pagesScored,
					unclear, risky, missedLineConfidences.size(), Map.copyOf(failures),
					Map.copyOf(textSources));
		}

		private static double rate(int part, int whole) {
			return whole == 0 ? 0 : (double) part / whole;
		}
	}

	/** One line of the tuning table. */
	private record Row(
			int psm,
			OcrCaseRenderer.Variant variant,
			OcrCaseRenderer.Typeface typeface,
			int pagesScored,
			double valueRecovery,
			double rangeRecovery,
			double nameRecovery,
			double piiRecovery,
			double meanConfidence,
			long millisPerCase,
			Map<Double, Double> unclearByThreshold,
			Map<Double, Double> riskyByThreshold,
			int missedValues,
			Map<String, Integer> failures,
			Map<TextSource, Integer> textSources) {
	}

	// --- output --------------------------------------------------------------

	private static String renderTable(List<Row> rows, Duration elapsed) {
		StringBuilder out = new StringBuilder();
		out.append("# OCR tuning sweep (phase 4, LLD 10.3)\n\n");
		out.append("Cases: ").append(CASE_IDS.size())
				.append(" · variants: ").append(OcrCaseRenderer.Variant.values().length)
				.append(" · modes: ").append(PAGE_SEGMENTATION_MODES)
				.append(" · elapsed: ").append(elapsed.toSeconds()).append("s\n\n");
		out.append("Model: the Debian/system `eng.traineddata` (no tessdata_best installed).\n\n");

		out.append("| psm | variant | font | pages | value | range | name | pii verbatim "
				+ "| mean conf | ms/case | unclear@70 | unclear@80 | unclear@90 "
				+ "| risky@70 | risky@80 | risky@90 | missed | failures |\n");
		out.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
				+ "---:|---:|---|\n");

		for (Row row : rows) {
			out.append("| ").append(row.psm())
					.append(" | ").append(row.variant().name().toLowerCase(Locale.ROOT)
							.replace('_', '-'))
					.append(" | ").append(row.typeface() == OcrCaseRenderer.Typeface.MONOSPACED
							? "mono" : "prop")
					.append(" | ").append(row.pagesScored())
					.append(" | ").append(percent(row.valueRecovery()))
					.append(" | ").append(percent(row.rangeRecovery()))
					.append(" | ").append(percent(row.nameRecovery()))
					.append(" | ").append(percent(row.piiRecovery()))
					.append(" | ").append(String.format(Locale.ROOT, "%.1f", row.meanConfidence()))
					.append(" | ").append(row.millisPerCase());
			for (double threshold : VALUE_CONFIDENCE_THRESHOLDS) {
				out.append(" | ").append(percent(row.unclearByThreshold().get(threshold)));
			}
			for (double threshold : VALUE_CONFIDENCE_THRESHOLDS) {
				out.append(" | ").append(percent(row.riskyByThreshold().get(threshold)));
			}
			out.append(" | ").append(row.missedValues())
					.append(" | ").append(row.failures().isEmpty() ? "-" : row.failures())
					.append(" |\n");
		}

		out.append("""

				## How to read this

				- **value / range / name** - the share of the answer key's printed strings that
				  came back out of OCR exactly. A value that did not survive is lost: the support
				  check drops whatever the model transcribes in its place.
				- **pii verbatim** - the share of planted personal data that survived OCR
				  unchanged. Lower is *worse for privacy*: a garbled name is a name the masking
				  rules will not match, and that is how an OCR report leaks.
				- **unclear@T** - of the values that did survive, the share that threshold refuses
				  to flag. The cost of safety, paid by every reader of a photo.
				- **risky@T** - of the values that did *not* survive, the share sitting on a row
				  OCR was confident about anyway. These are the candidates for a confident wrong
				  flag, so this is the number the threshold is chosen on.
				- **missed** - how many values the risky share is computed over, so a scary
				  percentage of two rows is visible as such.
				""");

		return out.toString();
	}

	private static String percent(Double rate) {
		return rate == null ? "-" : String.format(Locale.ROOT, "%.0f%%", rate * 100);
	}

	private static void write(String report) {
		Path directory = Paths.get("..", "eval", "results");
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
				.withZone(java.time.ZoneOffset.UTC).format(Instant.now());
		Path file = directory.resolve("ocr-tuning-" + stamp + ".md");
		try {
			Files.createDirectories(directory);
			Files.writeString(file, report, StandardCharsets.UTF_8);
			log.info("wrote {}", file.toAbsolutePath().normalize());
		}
		catch (IOException ex) {
			// The table is already in the log, so losing the file is not worth failing over.
			log.warn("could not write the tuning table: {}", ex.getClass().getSimpleName());
		}
	}

	private static void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			log.warn("could not delete a rendered case: {}", ex.getClass().getSimpleName());
		}
	}
}
