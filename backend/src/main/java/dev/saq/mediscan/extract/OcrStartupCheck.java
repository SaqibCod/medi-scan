package dev.saq.mediscan.extract;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Checks at startup that Tesseract is actually there, with English data.
 *
 * <p>Without this, a missing {@code tesseract} is discovered by the first visitor who uploads a
 * photo, as a failed report. The box either has OCR or it does not, and that is knowable at
 * boot in two sub-second process runs.
 *
 * <p><strong>Two behaviours, by profile.</strong> In {@code prod} a missing or broken Tesseract
 * with {@code ocr.enabled=true} fails startup: the image is built with Tesseract in it, so its
 * absence means the image is wrong and serving traffic that will fail is worse than not
 * starting. Anywhere else it logs a warning and reports itself unavailable, which turns image
 * uploads back into {@code 415} and skips the scanned-page fallback - the pre-phase-4 behaviour,
 * so a developer without Tesseract installed can still run everything else.
 *
 * <p>The version is logged on purpose. It is the one piece of OCR information that is not report
 * content, and knowing whether the box has 5.3.4 or 5.4.0 is the first question when output
 * changes ({@code docs/phase-4-lld.md} section 13).
 */
class OcrStartupCheck {

	private static final Logger log = LoggerFactory.getLogger(OcrStartupCheck.class);

	/** Generous for two trivial runs, bounded so a wedged binary cannot hang the boot. */
	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

	/** A version banner and a language list are both tiny; past this something is wrong. */
	private static final int PROBE_OUTPUT_CAP = 64 * 1024;

	private static final String REQUIRED_LANGUAGE = "eng";

	private final boolean available;
	private final String version;

	OcrStartupCheck(MediScanProperties properties, boolean required) {
		if (!properties.ocr().enabled()) {
			log.info("OCR disabled by configuration: images are refused and scanned pages are "
					+ "not read");
			this.available = false;
			this.version = null;
			return;
		}

		String command = properties.ocr().command();
		String detected = null;
		String problem = null;

		try {
			detected = probeVersion(command);
			if (!hasEnglish(command)) {
				problem = "the " + REQUIRED_LANGUAGE + " language data is not installed";
			}
		}
		catch (OcrFailedException ex) {
			// The reason enum, never a message: a start failure's message carries the path.
			problem = "running it failed (" + ex.reason() + ")";
		}

		if (problem != null) {
			// The command name is configuration, not user input, so naming it is safe and is
			// the only thing that makes this message actionable.
			String message = "OCR is enabled but '" + command + "' is not usable: " + problem;
			if (required) {
				throw new IllegalStateException(message
						+ ". Set OCR_ENABLED=false to run without OCR, or install tesseract-ocr "
						+ "and tesseract-ocr-" + REQUIRED_LANGUAGE + ".");
			}
			log.warn("{}. Continuing with OCR off: images will be refused with 415 and scanned "
					+ "pages will not be read.", message);
			this.available = false;
			this.version = null;
			return;
		}

		log.info("OCR available: {} (psm={}, threads={}, tessdata={})",
				detected, properties.ocr().psm(), properties.ocr().threads(),
				properties.ocr().tessdataDir() == null || properties.ocr().tessdataDir().isBlank()
						? "system"
						: properties.ocr().tessdataDir());

		this.available = true;
		this.version = detected;
	}

	/** Whether OCR can be used. False means images are refused as before this phase. */
	boolean available() {
		return available;
	}

	/** The detected version banner, or null when OCR is unavailable. */
	String version() {
		return version;
	}

	private static String probeVersion(String command) {
		ChildProcess.Result result = ChildProcess.run(List.of(command, "--version"),
				Map.of(), PROBE_TIMEOUT, PROBE_OUTPUT_CAP);

		if (result.exitCode() != 0) {
			throw new OcrFailedException(OcrFailedException.Reason.EXIT_CODE);
		}

		// "tesseract 5.4.0" on the first line, then the leptonica and codec banners.
		return result.stdout().lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty())
				.findFirst()
				.orElse("version unknown");
	}

	private static boolean hasEnglish(String command) {
		ChildProcess.Result result = ChildProcess.run(List.of(command, "--list-langs"),
				Map.of(), PROBE_TIMEOUT, PROBE_OUTPUT_CAP);

		if (result.exitCode() != 0) {
			throw new OcrFailedException(OcrFailedException.Reason.EXIT_CODE);
		}

		// The first line is a "List of available languages" header; the rest are codes.
		return result.stdout().lines()
				.map(line -> line.strip().toLowerCase(Locale.ROOT))
				.anyMatch(REQUIRED_LANGUAGE::equals);
	}
}
