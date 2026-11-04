package dev.saq.mediscan.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.saq.mediscan.mask.MaskRule;
import dev.saq.mediscan.mask.MaskRules;
import dev.saq.mediscan.mask.ProtectedSpans;
import dev.saq.mediscan.mask.Span;

/**
 * Masks what the caller typed, before it reaches the model ({@code LLD} 6.5).
 *
 * <p>Chat is the one place where a person types free text into this application, so it is the
 * one place where personal data can arrive without a lab report around it. The question and
 * every history turn go through the same personal-data patterns as report text on the way into
 * the prompt.
 *
 * <p><strong>A lighter rule set than {@link dev.saq.mediscan.mask.Masker}.</strong> Five rules:
 * SSN, email, identifier, birth date and phone. The two name rules are deliberately left out.
 * They key on labels - {@code Patient Name:}, {@code Ordered by:} - which a typed question
 * almost never contains, so they would contribute nothing while risking a false positive on
 * ordinary capitalised words. The OpenNLP person model is left out for a stronger reason: it
 * was trained on news text and would read capitalised test names as people, turning "Is my HDL
 * Cholesterol normal?" into a question about a redacted person.
 *
 * <p><strong>No protected spans.</strong> {@link dev.saq.mediscan.mask.Masker} protects lab
 * result rows first, because a phone pattern will happily match part of one. A typed question
 * has no result rows - the values live in {@code <results>}, which this never touches - so
 * there is nothing to protect and no integrity check to run.
 *
 * <p>This is a second line, not a licence. Masking is best-effort here exactly as it is
 * everywhere else, which is why {@code SECURITY.md} still asks people not to type anything
 * they would mind publishing. Nothing typed is logged or stored either way
 * ({@code CLAUDE.md} rules 9 and 19).
 */
@Component
public class TypedTextMasker {

	/**
	 * The rules that run on typed text, by their names in {@link MaskRules}.
	 *
	 * <p>Named rather than taken by position so that adding a rule to {@code MaskRules} - for
	 * report text, where it belongs - cannot silently change what happens to a question.
	 */
	private static final Set<String> RULE_NAMES =
			Set.of("ssn", "email", "identifier", "birthDate", "phone");

	private final List<MaskRule> rules;

	public TypedTextMasker() {
		this.rules = MaskRules.ordered().stream()
				.filter(rule -> RULE_NAMES.contains(rule.name()))
				.toList();

		if (rules.size() != RULE_NAMES.size()) {
			// A rule was renamed in MaskRules and this set was not updated. Failing at startup
			// beats silently masking less than intended for the rest of the deployment.
			throw new IllegalStateException(
					"expected " + RULE_NAMES.size() + " typed-text mask rules, found "
							+ rules.size() + "; MaskRules names have changed");
		}
	}

	/**
	 * Returns {@code text} with personal data replaced by placeholders.
	 *
	 * <p>Every rule sees the same original string and the replacements are applied in one pass
	 * from the right, so an earlier placeholder can never become a later rule's input and
	 * offsets stay valid - the same arrangement {@code Masker} uses, for the same reason.
	 */
	public String mask(String text) {
		if (text == null || text.isBlank()) {
			return text;
		}

		ProtectedSpans nothingProtected = ProtectedSpans.none();
		List<Span> accepted = new ArrayList<>();

		for (MaskRule rule : rules) {
			for (Span candidate : rule.find(text, nothingProtected)) {
				if (!overlapsAccepted(accepted, candidate)) {
					accepted.add(candidate);
				}
			}
		}

		if (accepted.isEmpty()) {
			return text;
		}

		accepted.sort((left, right) -> Integer.compare(right.start(), left.start()));

		StringBuilder masked = new StringBuilder(text);
		for (Span span : accepted) {
			masked.replace(span.start(), span.end(), span.type().placeholder());
		}
		return masked.toString();
	}

	/** An earlier, more precise rule already claimed this text. */
	private static boolean overlapsAccepted(List<Span> accepted, Span candidate) {
		return accepted.stream().anyMatch(span ->
				candidate.start() < span.end() && span.start() < candidate.end());
	}
}
