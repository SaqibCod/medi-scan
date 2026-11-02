package dev.saq.mediscan.extract;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Reads text with the {@code tesseract} command-line program, run as a child process.
 *
 * <p><strong>Why a child process and not Tess4J.</strong> The plan originally said Tess4J,
 * which binds to Tesseract through JNA. A segmentation fault in native code loaded into the JVM
 * takes the whole server down with it, and the input here is images uploaded by strangers. A
 * child process can be killed on a deadline, its memory is returned by the operating system
 * when it exits, and the worst it can do is fail its own page
 * ({@code docs/phase-4-lld.md} decision O1).
 *
 * <p><strong>Why TSV.</strong> {@code tsv} output gives the text and a per-word confidence from
 * a single run. The confidence is what the low-confidence rule needs, and running OCR twice to
 * get it is not affordable on a 2 GB box with one OCR permit.
 *
 * <p><strong>Why {@code --dpi} is always passed.</strong> The prepared PNGs carry no resolution
 * metadata, and without the flag Tesseract guesses - it prints {@code Estimating resolution as
 * 241} to stderr and segments accordingly. Passing the real figure keeps segmentation
 * predictable and keeps stderr empty on a healthy run.
 *
 * <p>Logging is a count line: duration, words, mean confidence, exit code. Never the text,
 * never the path, never stderr - Tesseract puts the input path in its error messages.
 */
class TesseractCliEngine implements OcrEngine {

	private static final Logger log = LoggerFactory.getLogger(TesseractCliEngine.class);

	/** The DPI the prepared images are rendered and scaled for. */
	private static final int DPI = 300;

	private final List<String> baseCommand;
	private final int psm;
	private final int maxOutputBytes;
	private final Map<String, String> environment;

	TesseractCliEngine(MediScanProperties properties) {
		this(List.of(properties.ocr().command()), properties);
	}

	/**
	 * @param baseCommand the program, as a list so a test can put a stand-in executable and its
	 *     arguments in front without this class ever building a shell string
	 */
	TesseractCliEngine(List<String> baseCommand, MediScanProperties properties) {
		this.baseCommand = List.copyOf(baseCommand);
		this.psm = properties.ocr().psm();
		this.maxOutputBytes = properties.ocr().maxOutputBytes();

		Map<String, String> env = new HashMap<>();
		// One OpenMP thread. The job pool already has two workers and the box has two vCPUs,
		// so letting Tesseract fan out takes the CPU the JVM needs to serve requests.
		// Measured on a rendered page: 2 and 4 threads were inside run-to-run noise.
		env.put("OMP_THREAD_LIMIT", String.valueOf(properties.ocr().threads()));

		String tessdataDir = properties.ocr().tessdataDir();
		if (tessdataDir != null && !tessdataDir.isBlank()) {
			env.put("TESSDATA_PREFIX", tessdataDir);
		}
		this.environment = Map.copyOf(env);
	}

	@Override
	public OcrPage recognize(Path image, Duration timeout) {
		List<String> command = new ArrayList<>(baseCommand);
		command.add(image.toString());
		command.add("stdout");
		command.add("-l");
		command.add("eng");
		command.add("--psm");
		command.add(String.valueOf(psm));
		command.add("--dpi");
		command.add(String.valueOf(DPI));
		command.add("tsv");

		long startedAt = System.nanoTime();
		ChildProcess.Result result =
				ChildProcess.run(command, environment, timeout, maxOutputBytes);
		long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

		if (result.exitCode() != 0) {
			log.info("ocr page failed: ms={} exit={} stderrBytes={}",
					durationMs, result.exitCode(), result.stderrLength());
			throw new OcrFailedException(OcrFailedException.Reason.EXIT_CODE);
		}

		OcrPage page = TsvLineBuilder.build(result.stdout());

		// The whole of what a page may say about itself. stderrBytes is a length, never the
		// bytes: Tesseract writes the input path into its errors.
		log.info("ocr page done: ms={} words={} meanConfidence={} exit=0 stderrBytes={}",
				durationMs, page.wordCount(), Math.round(page.meanConfidence()),
				result.stderrLength());

		return page;
	}
}
