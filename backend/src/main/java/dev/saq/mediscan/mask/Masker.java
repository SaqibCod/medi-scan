package dev.saq.mediscan.mask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.ReportErrorCode;
import dev.saq.mediscan.config.ReportFailure;

/**
 * Removes personal data from report text, without touching lab values.
 *
 * <p>The pipeline ({@code LLD} 10.1):
 *
 * <ol>
 * <li>find the result rows, which are protected;</li>
 * <li>run each rule in precision order, collecting spans it may have;</li>
 * <li>rewrite the text right to left, so earlier offsets stay valid;</li>
 * <li>verify no number on a result row changed.</li>
 * </ol>
 *
 * <p><strong>Every rule sees the same original string.</strong> No rule ever runs against
 * another rule's output, so a placeholder can never become an input, and offsets from
 * different rules are directly comparable. That is why replacement is a single pass at the end
 * rather than a fold.
 *
 * <p><strong>Step 4 is the one that matters.</strong> Steps 1 to 3 are heuristics; the
 * integrity check is a proof obligation. Masking is allowed to miss a name - that is a
 * best-effort claim the README and {@code SECURITY.md} both make honestly. It is not allowed
 * to alter a lab value, because that would put a wrong number in front of a patient. So the
 * numbers on every protected row are compared before and after, and a mismatch fails the
 * report rather than shipping it.
 */
@Component
public class Masker {

	private static final Logger log = LoggerFactory.getLogger(Masker.class);

	/** Numbers as the integrity check counts them, including decimals and comparators. */
	private static final Pattern NUMERIC_TOKEN = Pattern.compile("\\d+(?:[.,]\\d+)*");

	private final List<MaskRule> rules;

	/**
	 * @param openNlpRule present only when {@code mediscan.mask.opennlp-enabled} is true, so
	 *     the optional rule is absent rather than disabled at call time
	 */
	public Masker(ObjectProvider<OpenNlpNameRule> openNlpRule) {
		List<MaskRule> all = new ArrayList<>(MaskRules.ordered());
		// Appended last, after every rule that has real evidence. A statistical name finder
		// should only ever get the text nothing more precise explained.
		openNlpRule.ifAvailable(all::add);
		this.rules = List.copyOf(all);

		log.info("Masking with {} rules: {}", rules.size(), rules.stream().map(MaskRule::name).toList());
	}

	/**
	 * Masks {@code text}.
	 *
	 * @throws ReportFailure with {@code EXTRACTION_FAILED} if a lab value changed, which means
	 *     a rule is faulty and the result cannot be trusted
	 */
	public MaskResult mask(String text) {
		ProtectedSpans protectedRows = ResultRowDetector.detect(text);

		List<Span> accepted = new ArrayList<>();
		int conflicts = 0;

		for (MaskRule rule : rules) {
			for (Span candidate : rule.find(text, protectedRows)) {
				if (protectedRows.overlapsAny(candidate)) {
					// A rule reached into a lab result. Counted, never applied.
					conflicts++;
					continue;
				}
				if (overlapsAccepted(accepted, candidate)) {
					// An earlier, more precise rule already claimed this text.
					continue;
				}
				accepted.add(candidate);
			}
		}

		String masked = replace(text, accepted);
		verifyValuesUnchanged(text, masked, protectedRows);

		return new MaskResult(masked, countByType(accepted), conflicts);
	}

	private static boolean overlapsAccepted(List<Span> accepted, Span candidate) {
		return accepted.stream().anyMatch(candidate::overlaps);
	}

	/**
	 * Rewrites the text, replacing each span with its placeholder.
	 *
	 * <p>Right to left: replacing a span changes the length of everything after it, so working
	 * backwards means every remaining offset is still valid against the original string.
	 */
	private static String replace(String text, List<Span> spans) {
		List<Span> ordered = new ArrayList<>(spans);
		ordered.sort(null);

		StringBuilder builder = new StringBuilder(text);
		for (int i = ordered.size() - 1; i >= 0; i--) {
			Span span = ordered.get(i);
			builder.replace(span.start(), span.end(), span.type().placeholder());
		}
		return builder.toString();
	}

	private static Map<MaskType, Integer> countByType(List<Span> spans) {
		Map<MaskType, Integer> counts = new EnumMap<>(MaskType.class);
		for (Span span : spans) {
			counts.merge(span.type(), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Checks that no number on a result row changed.
	 *
	 * <p>Compares the numeric tokens of the protected rows in the original with those of the
	 * masked text, as ordered sequences. Masking only replaces spans outside protected rows,
	 * so these two sequences are identical unless a rule has a bug - which makes this a cheap
	 * assertion with a real failure mode behind it.
	 *
	 * <p>{@code LLD} 10.5 describes retrying with the offending span removed. That is not
	 * implemented, and deliberately: a mismatch here means a rule matched inside a result row
	 * despite the protected-span check, so the span bookkeeping itself is wrong. Retrying
	 * would be building on the thing that just proved untrustworthy. Failing the report is the
	 * honest outcome, and the condition is unreachable in normal operation.
	 */
	private static void verifyValuesUnchanged(String original, String masked,
			ProtectedSpans protectedRows) {

		if (protectedRows.count() == 0) {
			return;
		}

		List<String> before = numbersInProtectedRows(original, protectedRows);
		List<String> after = numbersIn(masked);

		// The masked text's numbers are a superset: it still holds everything from the
		// protected rows, plus any numbers from unprotected lines that no rule masked. So the
		// check is containment in order, not equality.
		if (!containsInOrder(after, before)) {
			// Counts only. The values themselves are exactly what must not be logged.
			log.error("Masking integrity check failed: {} protected numbers before, {} after",
					before.size(), after.size());
			throw new ReportFailure(ReportErrorCode.EXTRACTION_FAILED);
		}
	}

	private static List<String> numbersInProtectedRows(String text, ProtectedSpans protectedRows) {
		List<String> numbers = new ArrayList<>();
		for (Span row : protectedRows.spans()) {
			collectNumbers(text.substring(row.start(), row.end()), numbers);
		}
		return numbers;
	}

	private static List<String> numbersIn(String text) {
		List<String> numbers = new ArrayList<>();
		collectNumbers(text, numbers);
		return numbers;
	}

	private static void collectNumbers(String text, List<String> into) {
		Matcher matcher = NUMERIC_TOKEN.matcher(text);
		while (matcher.find()) {
			into.add(matcher.group());
		}
	}

	/** Whether {@code sequence} appears in {@code haystack} in order, allowing gaps. */
	private static boolean containsInOrder(List<String> haystack, List<String> sequence) {
		int index = 0;
		for (String value : haystack) {
			if (index < sequence.size() && value.equals(sequence.get(index))) {
				index++;
			}
		}
		return index == sequence.size();
	}
}
