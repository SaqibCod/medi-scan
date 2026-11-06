package dev.saq.mediscan.mask;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rule that is one pattern and one mask type.
 *
 * <p>Shared by the four rules that are genuinely just a regex - SSN, email, phone, and the
 * label-anchored ones - so that the parts which are easy to get subtly wrong are written once:
 * reporting the right capture group's offsets, skipping matches inside protected rows, and
 * never letting a pattern match the empty string into an infinite loop.
 *
 * <p>{@code group} exists because several patterns need context they must not mask. A birth
 * date is only a birth date next to a {@code DOB} label, but masking the label too would
 * destroy the structure the extraction model reads - so the pattern matches
 * {@code DOB: 04/12/1985} and reports only the date.
 *
 * <p>Protected result rows are <em>not</em> filtered here. Candidates are reported and
 * {@link Masker} decides, which is what {@link MaskRule} promises and what makes the conflict
 * count meaningful: a rule that silently dropped its own overlapping matches would leave the
 * masker nothing to count, and a rule reaching into lab values would go unnoticed.
 */
class RegexMaskRule implements MaskRule {

	private final String name;
	private final Pattern pattern;
	private final MaskType type;
	private final int group;

	RegexMaskRule(String name, Pattern pattern, MaskType type) {
		this(name, pattern, type, 0);
	}

	/**
	 * @param group the capture group to mask; 0 is the whole match
	 */
	RegexMaskRule(String name, Pattern pattern, MaskType type, int group) {
		this.name = name;
		this.pattern = pattern;
		this.type = type;
		this.group = group;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public List<Span> find(String text, ProtectedSpans protectedSpans) {
		List<Span> found = new ArrayList<>();
		Matcher matcher = pattern.matcher(text);

		while (matcher.find()) {
			int start = matcher.start(group);
			int end = matcher.end(group);

			// A group that did not participate in the match reports -1.
			if (start < 0 || end <= start) {
				continue;
			}
			found.add(new Span(start, end, type));
		}
		return found;
	}
}
