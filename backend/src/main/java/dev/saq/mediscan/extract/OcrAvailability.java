package dev.saq.mediscan.extract;

/**
 * Whether OCR can be used at all.
 *
 * <p>An interface with one production implementation ({@link OcrStartupCheck}) because the
 * answer is decided by probing a program at startup, and a unit test for anything downstream
 * should not need Tesseract installed to say "yes, assume it is there". Without this, the
 * extractor's tests would pass or fail depending on what is on the developer's PATH.
 */
interface OcrAvailability {

	/**
	 * False when OCR is switched off by configuration, or when no usable Tesseract was found on
	 * a box where that is not fatal. Images are then refused at upload exactly as they were
	 * before this phase.
	 */
	boolean available();
}
