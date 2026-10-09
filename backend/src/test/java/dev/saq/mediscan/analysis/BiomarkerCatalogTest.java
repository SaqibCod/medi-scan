package dev.saq.mediscan.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * Slug lookup for extracted test names ({@code LLD} 11.7).
 *
 * <p>The slug is what links a result row to its explanation, so the cases that matter are the
 * many ways a report can print the same marker - and the cases where nothing should match,
 * because pointing a reader at the wrong explanation is worse than no link.
 */
class BiomarkerCatalogTest {

	private final BiomarkerCatalog catalog = new BiomarkerCatalog(JsonMapper.builder().build());

	@Test
	@DisplayName("loads the index")
	void loadsIndex() {
		assertThat(catalog.size()).isGreaterThanOrEqualTo(20);
		assertThat(catalog.entries()).extracting(BiomarkerCatalog.Entry::slug)
				.contains("tsh", "free-t4", "hemoglobin", "total-cholesterol", "platelets");
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			// The canonical names, exactly as the samples print them.
			"Total Cholesterol,            total-cholesterol",
			"HDL Cholesterol,              hdl-cholesterol",
			"LDL Cholesterol,              ldl-cholesterol",
			"Non-HDL Cholesterol,          non-hdl-cholesterol",
			"Triglycerides,                triglycerides",
			"Hemoglobin,                   hemoglobin",
			"Hematocrit,                   hematocrit",
			"Platelet Count,               platelets",
			"TSH,                          tsh",
			"Free T4,                      free-t4",
			"Thyroid Peroxidase Antibodies,thyroid-peroxidase-antibodies",
			"WBC,                          wbc",
			"RBC,                          rbc",
			"MCV,                          mcv",
			"Neutrophils,                  neutrophils",
	})
	@DisplayName("matches the names the sample reports print")
	void matchesSampleNames(String testName, String expectedSlug) {
		assertThat(catalog.findSlug(testName)).contains(expectedSlug);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({
			// Aliases, which is why the index has them: reports print the same marker a
			// dozen ways.
			"HDL,                 hdl-cholesterol",
			"HDL-C,               hdl-cholesterol",
			"'Cholesterol, HDL',  hdl-cholesterol",
			"LDL-C,               ldl-cholesterol",
			"'Cholesterol, LDL',  ldl-cholesterol",
			"LDL Calculated,      ldl-cholesterol",
			"HGB,                 hemoglobin",
			"Haemoglobin,         hemoglobin",
			"PLT,                 platelets",
			"Platelets,           platelets",
			"Thyrotropin,         tsh",
			"FT4,                 free-t4",
			"'T4, Free',          free-t4",
			"TPO Ab,              thyroid-peroxidase-antibodies",
			"Anti-TPO,            thyroid-peroxidase-antibodies",
			"Leukocytes,          wbc",
			"Erythrocytes,        rbc",
	})
	@DisplayName("matches aliases")
	void matchesAliases(String testName, String expectedSlug) {
		assertThat(catalog.findSlug(testName)).contains(expectedSlug);
	}

	@ParameterizedTest(name = "{0} matches regardless of case and punctuation")
	@CsvSource({
			"hdl cholesterol,     hdl-cholesterol",
			"HDL-CHOLESTEROL,     hdl-cholesterol",
			"hdl-cholesterol,     hdl-cholesterol",
			"'  HDL  Cholesterol ',hdl-cholesterol",
			"free t4,             free-t4",
			"FREE-T4,             free-t4",
			"Free  T4,            free-t4",
			"tsh,                 tsh",
	})
	@DisplayName("normalises case, punctuation and spacing before matching")
	void normalisesBeforeMatching(String testName, String expectedSlug) {
		assertThat(catalog.findSlug(testName)).contains(expectedSlug);
	}

	@Test
	@DisplayName("a fully abbreviated name needs an alias, not cleverer normalisation")
	void dottedAbbreviationsNeedAnAlias() {
		// "T.S.H." normalises to "t s h", not "tsh", because punctuation becomes a space
		// rather than vanishing - which is what keeps "HDL-Cholesterol" and "HDL Cholesterol"
		// the same thing. Making punctuation vanish instead would give "hdlcholesterol" and
		// break the far more common case.
		//
		// So this is a miss, and the fix when a real report shows it is an alias in the
		// index, not a looser normaliser.
		assertThat(catalog.findSlug("T.S.H.")).isEmpty();
		assertThat(TestNameNormalizer.normalize("T.S.H.")).isEqualTo("t s h");
	}

	@ParameterizedTest(name = "{0} matches nothing")
	@ValueSource(strings = {
			// Not in the catalogue yet. A null slug is the correct answer: the row is still
			// kept and shown, it just does not link anywhere.
			"Ferritin", "Vitamin B12", "Hemoglobin A1c", "Creatinine", "ALT", "Cortisol",
			// Near misses that must not resolve. Pointing a reader at the explanation of a
			// different test is worse than no link at all.
			"Cholesterol", "T3", "Free T3", "Total T4", "Antibodies", "Count",
			// Noise.
			"[NAME]", "Reference Range", "Result", "---",
	})
	@DisplayName("returns empty rather than guessing at a near match")
	void refusesUnknownNames(String testName) {
		assertThat(catalog.findSlug(testName)).isEmpty();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\t", "!!!" })
	@DisplayName("handles missing and meaningless names")
	void handlesMissingNames(String testName) {
		assertThat(catalog.findSlug(testName)).isEmpty();
	}

	@Test
	@DisplayName("a slug resolves to itself, so a report printing one works")
	void slugResolvesToItself() {
		assertThat(catalog.findSlug("free-t4")).contains("free-t4");
		assertThat(catalog.findSlug("total-cholesterol")).contains("total-cholesterol");
	}

	@Test
	@DisplayName("normalisation agrees with what test_name_norm stores")
	void normalisationMatchesStoredColumn() {
		// The catalogue and the column must normalise identically, or a marker would get a
		// slug on one report and not the next, and phase 6 trends would treat them as
		// different tests.
		assertThat(TestNameNormalizer.normalize("HDL-Cholesterol")).isEqualTo("hdl cholesterol");
		assertThat(TestNameNormalizer.normalize("HDL Cholesterol")).isEqualTo("hdl cholesterol");
		assertThat(TestNameNormalizer.normalize("Free T4")).isEqualTo("free t4");
		assertThat(TestNameNormalizer.normalize("  TSH  ")).isEqualTo("tsh");
		assertThat(TestNameNormalizer.normalize(null)).isEmpty();
	}
}
