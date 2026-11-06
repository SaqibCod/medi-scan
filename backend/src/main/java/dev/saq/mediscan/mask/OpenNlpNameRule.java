package dev.saq.mediscan.mask;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import dev.saq.mediscan.config.MediScanProperties;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.tokenize.SimpleTokenizer;

/**
 * Finds person names with OpenNLP, for the ones the label rules cannot see.
 *
 * <p><strong>Off by default</strong> ({@code mediscan.mask.opennlp-enabled}). The only English
 * person-name model available is a 5 MB legacy artefact trained on news text, and lab reports
 * are not news text. The label-based rules in {@link MaskRules} are what the project relies
 * on; this is an addition with a cost, not the foundation. See
 * {@code src/main/resources/opennlp/README.md} and {@code SECURITY.md}.
 *
 * <p><strong>Threading.</strong> {@link TokenNameFinderModel} is thread-safe for evaluation
 * and holds essentially all of the 5 MB, so one instance is loaded at startup and shared.
 * {@link NameFinderME} is <em>not</em> thread-safe, so one is created per call and
 * {@code clearAdaptiveData()} is called when the document is done - without that, the
 * finder's adaptive features carry one report's names into the next, which on a shared
 * instance would be a cross-report data leak rather than merely a bug.
 *
 * <p><strong>Line by line.</strong> Each line is tokenised separately, so a name cannot be
 * inferred across a line break in a column layout, and token offsets map back to character
 * offsets in the original document.
 */
@Component
@ConditionalOnProperty(name = "mediscan.mask.opennlp-enabled", havingValue = "true")
public class OpenNlpNameRule implements MaskRule {

	private static final Logger log = LoggerFactory.getLogger(OpenNlpNameRule.class);

	/**
	 * Shared, immutable, and thread-safe for evaluation. This is the 5 MB.
	 */
	private final TokenNameFinderModel model;

	private final double minProbability;

	public OpenNlpNameRule(MediScanProperties properties, ResourceLoader resourceLoader) {
		this.minProbability = properties.mask().opennlpMinProbability();
		this.model = loadModel(properties.mask().opennlpModel(), resourceLoader);

		log.info("OpenNLP name rule enabled, minProbability={}", minProbability);
	}

	@Override
	public String name() {
		return "openNlpName";
	}

	@Override
	public List<Span> find(String text, ProtectedSpans protectedSpans) {
		// Per call, because NameFinderME is not thread-safe and two report jobs run at once.
		NameFinderME finder = new NameFinderME(model);
		List<Span> found = new ArrayList<>();

		try {
			int lineStart = 0;
			while (lineStart <= text.length()) {
				int newline = text.indexOf('\n', lineStart);
				int lineEnd = newline < 0 ? text.length() : newline;

				if (lineEnd > lineStart) {
					findInLine(finder, text, lineStart, lineEnd, protectedSpans, found);
				}

				if (newline < 0) {
					break;
				}
				lineStart = newline + 1;
			}
		}
		finally {
			// Must happen even on failure: adaptive data is per-document state, and leaving
			// it set would carry this report's names into whatever this finder saw next.
			finder.clearAdaptiveData();
		}

		return found;
	}

	private void findInLine(NameFinderME finder, String text, int lineStart, int lineEnd,
			ProtectedSpans protectedSpans, List<Span> found) {

		// A protected row is skipped outright rather than filtered afterwards. A result row
		// is the one place a false positive would change a number the patient sees.
		if (protectedSpans.overlapsAny(lineStart, lineEnd)) {
			return;
		}

		String line = text.substring(lineStart, lineEnd);

		// tokenizePos gives character offsets within the line, which is what lets a token
		// index coming back from find() be turned into a document offset.
		opennlp.tools.util.Span[] tokenSpans = SimpleTokenizer.INSTANCE.tokenizePos(line);
		if (tokenSpans.length == 0) {
			return;
		}

		String[] tokens = new String[tokenSpans.length];
		for (int i = 0; i < tokenSpans.length; i++) {
			tokens[i] = line.substring(tokenSpans[i].getStart(), tokenSpans[i].getEnd());
		}

		opennlp.tools.util.Span[] nameSpans = finder.find(tokens);
		if (nameSpans.length == 0) {
			return;
		}
		double[] probabilities = finder.probs(nameSpans);

		for (int i = 0; i < nameSpans.length; i++) {
			opennlp.tools.util.Span nameSpan = nameSpans[i];
			double probability = i < probabilities.length ? probabilities[i] : 0;

			if (probability < minProbability) {
				continue;
			}

			// find() reports token indices; convert to characters in the original document.
			int startInLine = tokenSpans[nameSpan.getStart()].getStart();
			int endInLine = tokenSpans[nameSpan.getEnd() - 1].getEnd();
			String candidate = line.substring(startInLine, endInLine);

			if (!isPlausibleName(candidate, nameSpan)) {
				continue;
			}

			found.add(new Span(lineStart + startInLine, lineStart + endInLine, MaskType.NAME));
		}
	}

	/**
	 * The false-positive filters ({@code LLD} 10.4).
	 *
	 * <p>Everything here exists because the model proposes things that are not names. The
	 * single-token rule is the strictest: one capitalised word is weak evidence on a document
	 * full of capitalised test names, so a lone token has to be at least three characters and
	 * must not be report vocabulary.
	 */
	private static boolean isPlausibleName(String candidate, opennlp.tools.util.Span nameSpan) {
		if (LabTerms.looksLikeLabTerm(candidate)) {
			return false;
		}
		// A name has letters. This drops spans that are a number, a code, or punctuation.
		if (candidate.chars().noneMatch(Character::isLetter)) {
			return false;
		}
		// Must start with a capital. Lab reports put names in title case; a lowercase match
		// is the model finding a pattern in prose.
		if (!Character.isUpperCase(candidate.charAt(0))) {
			return false;
		}
		// A run of two or more spaces is a column boundary, not part of a name. The model
		// works on tokens and cannot see the layout, so on a header line it happily reports
		// "Jane Q. Roe        Accession" as one entity - masking the next column's label
		// along with the name. Measured on all three samples before this check was added.
		if (candidate.contains("  ")) {
			return false;
		}
		// At least one word of two or more letters. This is what rejects "O.B." extracted
		// from a "D.O.B.:" label, which the model proposes as a person and which the token
		// count alone does not catch, because the periods make it several tokens. A real
		// name always has a word with some letters in it.
		if (!hasSubstantialWord(candidate)) {
			return false;
		}

		int tokenCount = nameSpan.getEnd() - nameSpan.getStart();
		if (tokenCount == 1) {
			return candidate.length() >= 3 && candidate.chars().allMatch(
					character -> Character.isLetter(character) || character == '\''
							|| character == '-');
		}
		return true;
	}

	/** Whether any whitespace- or period-separated word has two or more letters. */
	private static boolean hasSubstantialWord(String candidate) {
		for (String word : candidate.split("[^A-Za-z]+")) {
			if (word.length() >= 2) {
				return true;
			}
		}
		return false;
	}

	private static TokenNameFinderModel loadModel(String location, ResourceLoader resourceLoader) {
		Resource resource = resourceLoader.getResource(location);
		if (!resource.exists()) {
			// Fail fast and say exactly what to do. The alternative - masking silently
			// running one rule short - is the kind of degradation a privacy feature must
			// never do quietly.
			throw new IllegalStateException(
					"mediscan.mask.opennlp-enabled is true but the model is missing from '"
							+ location + "'. See src/main/resources/opennlp/README.md for the "
							+ "download URL and checksum, or set the property to false.");
		}

		try (InputStream in = resource.getInputStream()) {
			return new TokenNameFinderModel(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException(
					"could not load the OpenNLP person-name model from '" + location + "'", ex);
		}
	}
}
