package dev.saq.mediscan.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.support.LogCapture;
import dev.saq.mediscan.support.TestProperties;

/**
 * {@link TesseractCliEngine} against a real child process that is not Tesseract.
 *
 * <p>The stand-in is {@link FakeTesseract}, launched with the JVM running these tests. That is
 * deliberate: it exercises the actual {@code ProcessBuilder} path, pipe draining, killing and
 * reaping on both Windows and Linux, with <strong>no shell involved</strong> - which a
 * {@code .bat} or {@code .sh} stand-in could not claim, since launching either needs one.
 *
 * <p>These tests are about the plumbing, not the parsing: what happens when a process hangs,
 * floods its output, dies badly, or says something on stderr that must never be logged.
 */
class TesseractCliEngineTest {

	/** The JVM running these tests, used as the stand-in executable. */
	private static List<String> fakeTesseract(String mode) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return List.of(java, "-cp", System.getProperty("java.class.path"),
				FakeTesseract.class.getName(), mode);
	}

	private static TesseractCliEngine engine(String mode) {
		return engine(mode, TestProperties.defaults());
	}

	private static TesseractCliEngine engine(String mode, MediScanProperties properties) {
		return new TesseractCliEngine(fakeTesseract(mode), properties);
	}

	private static final Path IMAGE = Path.of("page.png");

	@Test
	@DisplayName("valid TSV output becomes a page with its words and confidences")
	void readsValidOutput() {
		OcrPage page = engine("tsv").recognize(IMAGE, Duration.ofSeconds(30));

		assertThat(page.wordCount()).isEqualTo(4);
		assertThat(page.text()).isEqualTo("Potassium   5.4   mmol/L   3.5-5.0");
		assertThat(page.meanConfidence()).isBetween(93.0, 94.0);
	}

	@Test
	@DisplayName("a blank page is an empty result, not a failure")
	void blankPageIsEmpty() {
		// Tesseract exits 0 on an image it found no text in. That is an empty page for the
		// caller to judge, not an error for this class to raise.
		OcrPage page = engine("empty").recognize(IMAGE, Duration.ofSeconds(30));

		assertThat(page.isEmpty()).isTrue();
		assertThat(page.meanConfidence()).isZero();
	}

	@Test
	@DisplayName("a process that overruns its timeout is killed, and the page fails")
	void timeoutKillsTheProcess() {
		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> engine("sleep").recognize(IMAGE, Duration.ofMillis(800)))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.TIMEOUT);

		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

		// The child sleeps for ten minutes. Returning in anything close to the timeout proves
		// the deadline was enforced rather than waited out.
		assertThat(elapsedMs)
				.as("must return on the deadline, not when the child finishes")
				.isLessThan(30_000);
	}

	@Test
	@DisplayName("a process that floods stdout is killed at the cap")
	void outputCapKillsTheProcess() {
		// The fake writes ~400 MB if left alone. The cap has to stop it, and stop it by
		// killing rather than by reading to the end and discarding.
		MediScanProperties properties = TestProperties.withOcrOutputCap(64 * 1024);

		long startedAt = System.nanoTime();

		assertThatThrownBy(() -> engine("flood", properties)
				.recognize(IMAGE, Duration.ofSeconds(60)))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.OUTPUT_CAP);

		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
		assertThat(elapsedMs)
				.as("the cap must end the run promptly, not after draining 400 MB")
				.isLessThan(30_000);
	}

	@Test
	@DisplayName("a non-zero exit fails the page")
	void nonZeroExitFails() {
		assertThatThrownBy(() -> engine("exit1").recognize(IMAGE, Duration.ofSeconds(30)))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.EXIT_CODE);
	}

	@Test
	@DisplayName("a missing program fails the page rather than throwing something unhandled")
	void missingProgramFails() {
		MediScanProperties properties = TestProperties.defaults();
		TesseractCliEngine missing = new TesseractCliEngine(
				List.of("definitely-not-a-program-" + System.nanoTime()), properties);

		assertThatThrownBy(() -> missing.recognize(IMAGE, Duration.ofSeconds(5)))
				.isInstanceOf(OcrFailedException.class)
				.extracting(failure -> ((OcrFailedException) failure).reason())
				.isEqualTo(OcrFailedException.Reason.START_FAILED);
	}

	@Test
	@DisplayName("arguments reach the program literally, so there is no shell to exploit")
	void argumentsArePassedAsAList() {
		// A file name full of characters a shell would act on. Kept to characters Windows
		// also allows in a path, so this runs everywhere: no asterisk, no quote, no pipe.
		// The temp files are named by UUID so this cannot happen in production - which is the
		// point: the defence is that no shell exists, not that the names are tame.
		Path hostile = Path.of("page; rm -rf tmp && echo $(whoami) `id`.png");

		OcrPage echoed = engine("echo-args").recognize(hostile, Duration.ofSeconds(30));

		// echo-args emits one argument per line. The path has to arrive as one unsplit,
		// unexpanded argument.
		List<String> arguments = new ArrayList<>(echoed.lines().stream()
				.map(OcrLine::text)
				.toList());

		assertThat(arguments).contains(hostile.toString());
		assertThat(arguments).contains("stdout", "eng", "tsv");
		// Nothing was expanded or split: no shell ran, so the substitutions are still text and
		// the semicolon did not end a command.
		assertThat(String.join("\n", arguments))
				.contains("$(whoami)")
				.contains("`id`")
				.contains("rm -rf tmp");
	}

	@Test
	@DisplayName("--dpi is always passed, so Tesseract never guesses the resolution")
	void alwaysPassesDpi() {
		// Without it, Tesseract estimates from the image and says so on stderr. The prepared
		// PNGs carry no resolution metadata, so this flag is not optional.
		OcrPage echoed = engine("echo-args").recognize(IMAGE, Duration.ofSeconds(30));

		assertThat(echoed.text()).contains("--dpi").contains("300");
	}

	@Test
	@DisplayName("the configured page segmentation mode is what gets passed")
	void passesConfiguredPsm() {
		MediScanProperties properties = TestProperties.withOcr(
				new MediScanProperties.Ocr(true, "tesseract", "", 6, 1,
						Duration.ofSeconds(45), Duration.ofSeconds(150), 10,
						40_000_000L, 67_108_864L, 1500, 4000, 2_097_152, 30, 80));

		OcrPage echoed = engine("echo-args", properties).recognize(IMAGE, Duration.ofSeconds(30));

		List<String> arguments = echoed.lines().stream().map(OcrLine::text).toList();
		int psmFlag = arguments.indexOf("--psm");
		assertThat(psmFlag).isNotNegative();
		assertThat(arguments.get(psmFlag + 1)).isEqualTo("6");
	}

	@Test
	@DisplayName("nothing the process says on stderr reaches the log")
	void stderrNeverLogged() {
		try (LogCapture logs = LogCapture.start()) {
			OcrPage page = engine("stderr").recognize(IMAGE, Duration.ofSeconds(30));

			assertThat(page.wordCount()).isEqualTo(4);

			// Tesseract writes the input path into its errors, so stderr is read only to keep
			// the pipe clear. Its length may be logged; its content may not.
			assertThat(logs.allText())
					.as("stderr content must never reach a log line")
					.doesNotContain(FakeTesseract.STDERR_CANARY)
					.doesNotContain("Estimating resolution")
					.contains("stderrBytes=");
		}
	}

	@Test
	@DisplayName("the log line carries counts and timings, never the text that was read")
	void logsOnlyCounts() {
		try (LogCapture logs = LogCapture.start()) {
			engine("tsv").recognize(IMAGE, Duration.ofSeconds(30));

			String logged = logs.allText();

			assertThat(logged)
					.contains("ocr page done")
					.contains("words=4")
					.contains("meanConfidence=");
			// The words that were read, and the path they came from.
			assertThat(logged)
					.doesNotContain("Potassium")
					.doesNotContain("5.4")
					.doesNotContain("mmol/L")
					.doesNotContain("page.png");
		}
	}

	@Test
	@DisplayName("the failure carries a reason and no report content")
	void failureCarriesNoContent() {
		OcrFailedException failure = new OcrFailedException(OcrFailedException.Reason.TIMEOUT);

		// This message is the one thing a caller might log about a failure.
		assertThat(failure.getMessage()).isEqualTo("ocr failed: TIMEOUT");
		assertThat(failure.reason()).isEqualTo(OcrFailedException.Reason.TIMEOUT);
	}
}
