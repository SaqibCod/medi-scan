package dev.saq.mediscan.mask;

import java.util.Locale;
import java.util.Set;

/**
 * Words that appear on lab reports and must never be masked as names.
 *
 * <p>Exists for {@link OpenNlpNameRule}. A person-name model trained on news text has no idea
 * what a lab report is, and reliably proposes test names and column headings as people -
 * {@code Free T4} and {@code Vitamin B12} look a great deal like names to it. Masking a test
 * name would be worse than missing a person's: the extraction step would lose the row, and the
 * patient would silently not be shown a result they have.
 *
 * <p>Deliberately local to {@code mask} rather than read from the biomarker catalogue, which
 * lives in {@code analysis} - {@code mask} must not depend on it
 * ({@code backend/CLAUDE.md}, "Dependency direction"). The duplication is small and the
 * coupling it avoids is not: this list is about what a name finder gets wrong, which is a
 * different question from which biomarkers have curated pages.
 */
final class LabTerms {

	/**
	 * Lowercased single tokens.
	 *
	 * <p>Checked per token as well as against the whole span, so a two-word span containing
	 * any of these is rejected - which is what catches {@code Free T4} and
	 * {@code Vitamin B12} without listing every combination.
	 */
	private static final Set<String> TOKENS = Set.of(
			// Column headings and report structure.
			"test", "tests", "result", "results", "unit", "units", "reference", "range",
			"ranges", "flag", "flags", "value", "values", "comment", "comments", "note",
			"notes", "method", "methods", "status", "final", "interim", "normal", "abnormal",
			"high", "low", "negative", "positive", "trace", "reactive", "detected",
			"specimen", "sample", "collected", "received", "reported", "drawn", "fasting",
			"laboratory", "lab", "clinical", "pathology", "panel", "profile", "screen",
			"serum", "plasma", "blood", "urine", "whole", "page", "performed",

			// Analytes and their common name-like tokens.
			"cholesterol", "triglycerides", "hdl", "ldl", "vldl", "glucose", "hemoglobin",
			"haemoglobin", "hematocrit", "haematocrit", "platelet", "platelets", "neutrophils",
			"lymphocytes", "monocytes", "eosinophils", "basophils", "sodium", "potassium",
			"chloride", "calcium", "magnesium", "phosphorus", "creatinine", "albumin",
			"bilirubin", "protein", "globulin", "urea", "ferritin", "transferrin", "folate",
			"insulin", "cortisol", "testosterone", "estradiol", "thyroid", "peroxidase",
			"antibodies", "antibody", "vitamin", "iron", "zinc", "copper",

			// Abbreviations a name finder is especially fond of.
			"tsh", "wbc", "rbc", "mcv", "mch", "mchc", "rdw", "mpv", "inr", "ast", "alt",
			"alp", "ggt", "ldh", "ck", "bun", "egfr", "hba1c", "a1c", "crp", "esr", "psa",
			"pth", "fsh", "lh", "t3", "t4", "free", "total", "direct", "indirect", "non",
			"calc", "calculated", "est", "estimated", "ratio", "count", "absolute", "auto");

	private LabTerms() {
	}

	/**
	 * Whether {@code candidate} looks like report vocabulary rather than a person.
	 *
	 * <p>True if the whole span is a known term, or if any of its tokens is. The second
	 * condition is the useful one: a span is rejected when it contains report vocabulary at
	 * all, because a real name does not.
	 */
	static boolean looksLikeLabTerm(String candidate) {
		String normalized = candidate.toLowerCase(Locale.ROOT).strip();
		if (normalized.isEmpty() || TOKENS.contains(normalized)) {
			return true;
		}

		for (String token : normalized.split("[^a-z0-9]+")) {
			if (!token.isEmpty() && TOKENS.contains(token)) {
				return true;
			}
		}
		return false;
	}
}
