package dev.saq.mediscan.extract;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;

import dev.saq.mediscan.support.TestProperties;

/**
 * Whether this machine has a Tesseract the {@code ocr}-tagged tests can use.
 *
 * <p>Those tests measure what OCR actually reads, so they cannot be faked and cannot be made to
 * pass without the real program. They still belong in {@code ./mvnw verify}
 * ({@code docs/phase-4-lld.md} 12.2), which means the suite has to behave sensibly on a machine
 * that does not have it - skipping with a message that says what to install, rather than failing
 * with a stack trace that looks like a bug in the pipeline.
 *
 * <p>Checked once per JVM: starting a process to ask is cheap, but not cheap enough to do it
 * before every test in a class.
 */
public final class TesseractAvailable {

	private static final boolean AVAILABLE = probe();

	private TesseractAvailable() {
	}

	/** Skips the calling test, with an actionable message, when Tesseract is missing. */
	public static void orSkip() {
		Assumptions.assumeTrue(AVAILABLE,
				"needs the `tesseract` command with the `eng` model on the PATH: "
						+ "`apt-get install tesseract-ocr tesseract-ocr-eng`, "
						+ "or run these tests inside the backend image");
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/**
	 * Asks the configured command for its version, and for English.
	 *
	 * <p>Both, because the two fail differently and only one of them is obvious: a box with the
	 * program and no model runs every test right up to the first page of OCR.
	 */
	private static boolean probe() {
		String command = TestProperties.defaultOcr().command();
		return runs(command, "--version") && listsEnglish(command);
	}

	private static boolean runs(String command, String argument) {
		try {
			Process process = new ProcessBuilder(command, argument)
					.redirectErrorStream(true)
					.start();
			process.getInputStream().readAllBytes();
			return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static boolean listsEnglish(String command) {
		try {
			Process process = new ProcessBuilder(command, "--list-langs")
					.redirectErrorStream(true)
					.start();
			String output = new String(process.getInputStream().readAllBytes());
			return process.waitFor(20, TimeUnit.SECONDS)
					&& output.lines().anyMatch(line -> line.strip().equals("eng"));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception ex) {
			return false;
		}
	}
}
