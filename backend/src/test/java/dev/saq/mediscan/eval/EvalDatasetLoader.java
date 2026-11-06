package dev.saq.mediscan.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.report.ReportStatus;

/**
 * Reads {@code eval/cases/} into matched pairs of case metadata and answer key.
 *
 * <p>Repo-root relative, the same way {@code ApiContractTest} finds {@code
 * docs/api-contract.md}: tests run with {@code backend/} as the working directory, and the
 * dataset is committed at the repo root so a portfolio reader can see it next to {@code
 * docs/evaluation.md} ({@code docs/phase-3-lld.md} decision E7).
 *
 * <p>Refuses to load a case that is not marked {@code "synthetic": true}, or whose folder name
 * does not match its {@code id} - both LLD section 9 requirements enforced here rather than in
 * a separate assertion, since there would otherwise be nothing to assert on.
 */
public final class EvalDatasetLoader {

	/** {@code eval/cases}, resolved from the Maven working directory. */
	public static final Path CASES_DIR = Paths.get("..", "eval", "cases");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private EvalDatasetLoader() {
	}

	/** One case: its metadata and report text, paired with its answer key. */
	public record Entry(EvalCase evalCase, ExpectedResult expected) {
	}

	/** Loads every case directory under {@link #CASES_DIR}, sorted by id. */
	public static List<Entry> loadAll() {
		return loadAll(CASES_DIR);
	}

	/**
	 * @throws IllegalStateException when a case is missing a required file, is not marked
	 *     synthetic, or its id does not match its folder name
	 */
	public static List<Entry> loadAll(Path casesDir) {
		if (!Files.isDirectory(casesDir)) {
			throw new IllegalStateException(
					"no eval cases directory at " + casesDir.toAbsolutePath());
		}
		try (Stream<Path> dirs = Files.list(casesDir)) {
			return dirs.filter(Files::isDirectory).sorted().map(EvalDatasetLoader::loadOne).toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Entry loadOne(Path dir) {
		String folderName = dir.getFileName().toString();

		CaseMetadata metadata = readJson(dir.resolve("case.json"), CaseMetadata.class);
		if (!metadata.synthetic()) {
			throw new IllegalStateException(
					"case " + folderName + " is not marked \"synthetic\": true");
		}
		if (!metadata.id().equals(folderName)) {
			throw new IllegalStateException("case folder \"" + folderName
					+ "\" has case.json id \"" + metadata.id() + "\" - they must match");
		}

		String reportText = readText(dir.resolve("report.txt"));
		ExpectedResultJson expectedJson =
				readJson(dir.resolve("expected.json"), ExpectedResultJson.class);

		EvalCase evalCase = new EvalCase(metadata.id(), metadata.title(), metadata.synthetic(),
				metadata.format(), metadata.tags(), metadata.expectedStatus(),
				metadata.expectedErrorCode(), reportText);

		ExpectedResult expected = new ExpectedResult(parseDate(expectedJson.collectedOn()),
				expectedJson.biomarkers(), expectedJson.pii(), expectedJson.injection());

		return new Entry(evalCase, expected);
	}

	private static LocalDate parseDate(String text) {
		return (text == null || text.isBlank()) ? null : LocalDate.parse(text);
	}

	private static <T> T readJson(Path file, Class<T> type) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("missing " + file);
		}
		try {
			return JSON.readValue(Files.readString(file), type);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + file, ex);
		}
	}

	private static String readText(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException("missing " + file);
		}
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + file, ex);
		}
	}

	/** The subset of {@code case.json} that is not already implied by the folder. */
	private record CaseMetadata(String id, String title, boolean synthetic, EvalCase.Format format,
			List<String> tags, ReportStatus expectedStatus, ReportErrorCode expectedErrorCode) {
	}

	/** {@code expected.json} before {@code collectedOn} is parsed into a date. */
	private record ExpectedResultJson(String collectedOn,
			List<ExpectedResult.ExpectedBiomarker> biomarkers, List<ExpectedResult.PlantedPii> pii,
			String injection) {
	}
}
