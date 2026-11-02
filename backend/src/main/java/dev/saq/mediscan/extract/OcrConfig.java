package dev.saq.mediscan.extract;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Wires the OCR pieces.
 *
 * <p>A configuration class rather than annotations on each type, for two reasons. The engine
 * takes its base command as a list so tests can put a stand-in executable in front of it, which
 * means its production arguments belong in one obvious place. And whether a missing Tesseract
 * should stop the boot is a profile question, which is answered here once rather than consulted
 * from inside a component.
 */
@Configuration(proxyBeanMethods = false)
class OcrConfig {

	/**
	 * Runs the startup probe.
	 *
	 * <p>Eager by construction: the check runs while this bean is created, so a {@code prod} box
	 * with a broken image fails during startup rather than on the first upload.
	 */
	@Bean
	OcrStartupCheck ocrStartupCheck(MediScanProperties properties, Environment environment) {
		// Required in prod only. Elsewhere a developer without tesseract installed still gets a
		// working application, minus OCR.
		boolean required = environment.matchesProfiles("prod");
		return new OcrStartupCheck(properties, required);
	}

	@Bean
	OcrEngine ocrEngine(MediScanProperties properties) {
		return new TesseractCliEngine(properties);
	}
}
