package dev.saq.mediscan.extract;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;
import dev.saq.mediscan.report.SourceType;

/**
 * Handles the two sources that are already text: pasted text and bundled samples.
 *
 * <p>Still applies the length checks. Pasted text was already bounded at upload, but a sample
 * never passed through that check, and both end up in the same prompt - so the limit is
 * enforced where the text is produced rather than trusted from upstream.
 */
@Component
public class PlainTextExtractor implements TextExtractor {

	private final int minTotalChars;
	private final int maxTextChars;

	public PlainTextExtractor(MediScanProperties properties) {
		this.minTotalChars = properties.extract().minTotalChars();
		this.maxTextChars = properties.upload().maxTextChars();
	}

	@Override
	public boolean supports(JobSource source) {
		return source instanceof JobSource.RawText || source instanceof JobSource.Sample;
	}

	@Override
	public boolean supports(SourceType sourceType) {
		return sourceType == SourceType.TEXT || sourceType == SourceType.SAMPLE;
	}

	@Override
	public ExtractedText extract(JobSource source) {
		String raw = switch (source) {
			case JobSource.RawText(String text) -> text;
			case JobSource.Sample sample -> sample.text();
			default -> throw new IllegalArgumentException(
					"PlainTextExtractor cannot read " + source.getClass().getSimpleName());
		};

		String text = TextNormalizer.normalize(raw);

		if (text.length() < minTotalChars) {
			throw new ReportFailure(ReportErrorCode.UNREADABLE);
		}
		if (text.length() > maxTextChars) {
			throw new ReportFailure(ReportErrorCode.DOCUMENT_TOO_LONG);
		}
		return ExtractedText.ofPlainText(text);
	}
}
