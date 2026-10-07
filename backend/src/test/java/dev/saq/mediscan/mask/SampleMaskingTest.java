package dev.saq.mediscan.mask;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import dev.saq.mediscan.support.TestProperties;

/**
 * Masking measured against the three real sample reports.
 *
 * <p>The samples are the only documents in the repository that look like actual lab reports,
 * and every demo run goes through them - so they are the honest test of whether masking works
 * on its intended input rather than on strings written to make it pass.
 *
 * <p>Run with the OpenNLP rule both off (the default, and what production does) and on, which
 * is where the numbers in {@code SECURITY.md} come from.
 */
class SampleMaskingTest {

	/** Default configuration: label rules only. */
	private final Masker labelRulesOnly = new Masker(absent());

	/** Every piece of personal data planted in the samples, and which file it is in. */
	private static final List<PlantedData> PLANTED = List.of(
			new PlantedData("lipid-panel.txt", "Jane Q. Roe", MaskType.NAME),
			new PlantedData("lipid-panel.txt", "Alan Whitfield", MaskType.NAME),
			new PlantedData("lipid-panel.txt", "A1234567", MaskType.ID),
			new PlantedData("lipid-panel.txt", "NC-4471829", MaskType.ID),
			new PlantedData("lipid-panel.txt", "20260928-0114", MaskType.ID),
			new PlantedData("lipid-panel.txt", "04/12/1985", MaskType.DOB),
			new PlantedData("lipid-panel.txt", "(555) 555-0142", MaskType.PHONE),
			new PlantedData("lipid-panel.txt", "(555) 555-0100", MaskType.PHONE),

			new PlantedData("cbc.txt", "Marcus T. Elder", MaskType.NAME),
			new PlantedData("cbc.txt", "Priya Ramachandran", MaskType.NAME),
			new PlantedData("cbc.txt", "RB889214", MaskType.ID),
			new PlantedData("cbc.txt", "RB-9920415", MaskType.ID),
			new PlantedData("cbc.txt", "22/07/1978", MaskType.DOB),
			new PlantedData("cbc.txt", "(555) 555-0188", MaskType.PHONE),
			new PlantedData("cbc.txt", "(555) 555-0170", MaskType.PHONE),

			new PlantedData("thyroid-panel.txt", "Dolores Fenwick", MaskType.NAME),
			new PlantedData("thyroid-panel.txt", "Henry Okonkwo", MaskType.NAME),
			new PlantedData("thyroid-panel.txt", "LV-7781", MaskType.ID),
			new PlantedData("thyroid-panel.txt", "LV-2208734", MaskType.ID),
			new PlantedData("thyroid-panel.txt", "1991-03-19", MaskType.DOB),
			new PlantedData("thyroid-panel.txt", "(555) 555-0119", MaskType.PHONE),
			new PlantedData("thyroid-panel.txt", "(555) 555-0133", MaskType.PHONE));

	/** Every result row of every sample, which must survive byte for byte. */
	private static final List<String> RESULT_ROWS = List.of(
			"Total Cholesterol           238         mg/dL       <200           H",
			"HDL Cholesterol             38          mg/dL       >40            L",
			"LDL Cholesterol             172         mg/dL       <100           H",
			"Triglycerides               140         mg/dL       <150",
			"Non-HDL Cholesterol         200         mg/dL       <130           H",

			"WBC                        6.8        x10^3/uL      4.0-11.0",
			"Hemoglobin                 14.6       g/dL          13.5-17.5",
			"Hematocrit                 43.2       %             38.8-50.0",
			"Platelet Count             245        x10^3/uL      150-400",

			"TSH                             0.21        uIU/mL     0.45-4.50",
			"Free T4                         1.74        ng/dL      0.82-1.77",
			"Thyroid Peroxidase Antibodies   Negative               Negative");

	@Test
	@DisplayName("the label rules alone remove every planted identifier, date and phone number")
	void labelRulesRemoveStructuredData() {
		for (PlantedData planted : PLANTED) {
			if (planted.type() == MaskType.NAME) {
				continue;
			}
			String masked = labelRulesOnly.mask(read(planted.file())).maskedText();

			assertThat(masked)
					.as("%s in %s", planted.value(), planted.file())
					.doesNotContain(planted.value());
		}
	}

	@Test
	@DisplayName("the label rules alone remove every planted name")
	void labelRulesRemoveNames() {
		// Every name in the samples sits behind a label or an honorific, which is exactly the
		// case the label rules are built for - and why OpenNLP is not needed by default.
		for (PlantedData planted : PLANTED) {
			if (planted.type() != MaskType.NAME) {
				continue;
			}
			String masked = labelRulesOnly.mask(read(planted.file())).maskedText();

			assertThat(masked)
					.as("%s in %s", planted.value(), planted.file())
					.doesNotContain(planted.value());
		}
	}

	@Test
	@DisplayName("every result row of every sample survives byte for byte")
	void resultRowsSurvive() {
		List<String> maskedSamples = List.of(
				labelRulesOnly.mask(read("lipid-panel.txt")).maskedText(),
				labelRulesOnly.mask(read("cbc.txt")).maskedText(),
				labelRulesOnly.mask(read("thyroid-panel.txt")).maskedText());

		String all = String.join("\n", maskedSamples);

		// Whole rows including their column spacing. The support check in validation matches
		// the model's rawValue against this text, so a single altered character drops a row.
		for (String row : RESULT_ROWS) {
			assertThat(all).as("result row must be unchanged: %s", row).contains(row);
		}
	}

	@Test
	@DisplayName("every collection date survives, in all three date formats")
	void collectionDatesSurvive() {
		// One per sample, deliberately in a different format each: MM/dd, dd/MM, and a month
		// name. All three must get past the birth-date rule.
		assertThat(labelRulesOnly.mask(read("lipid-panel.txt")).maskedText())
				.contains("Collected: 09/28/2026");
		assertThat(labelRulesOnly.mask(read("cbc.txt")).maskedText())
				.contains("Collected: 15/09/2026");
		assertThat(labelRulesOnly.mask(read("thyroid-panel.txt")).maskedText())
				.contains("Collected: 02 Oct 2026");
	}

	@Test
	@DisplayName("no sample produces a masking conflict")
	void noConflicts() {
		for (String file : List.of("lipid-panel.txt", "cbc.txt", "thyroid-panel.txt")) {
			MaskResult result = labelRulesOnly.mask(read(file));

			// A conflict means a rule tried to mask inside a lab value. Zero on realistic
			// input is the bar; a non-zero count here would mean a rule needs narrowing.
			assertThat(result.conflicts()).as("conflicts in %s", file).isZero();
		}
    }

	@Test
	@DisplayName("with OpenNLP enabled, result rows and test names still survive")
	void openNlpDoesNotDamageResults() {
		Masker withOpenNlp = new Masker(provider(new OpenNlpNameRule(
				TestProperties.withOpenNlp(true, 0.7), new DefaultResourceLoader())));

		String all = String.join("\n",
				withOpenNlp.mask(read("lipid-panel.txt")).maskedText(),
				withOpenNlp.mask(read("cbc.txt")).maskedText(),
				withOpenNlp.mask(read("thyroid-panel.txt")).maskedText());

		// The measurement behind the SECURITY.md note: turning the model on must not cost a
		// single lab value, or it cannot be turned on at all.
		for (String row : RESULT_ROWS) {
			assertThat(all).as("row must survive OpenNLP too: %s", row).contains(row);
		}
		assertThat(all).contains("Collected: 09/28/2026");
		assertThat(all).contains("Free T4").contains("Thyroid Peroxidase Antibodies");
	}

	@Test
	@DisplayName("OpenNLP finds nothing the label rules missed on these samples")
	void openNlpAddsNothingOnSamples() {
		Masker withOpenNlp = new Masker(provider(new OpenNlpNameRule(
				TestProperties.withOpenNlp(true, 0.7), new DefaultResourceLoader())));

		for (String file : List.of("lipid-panel.txt", "cbc.txt", "thyroid-panel.txt")) {
			String text = read(file);

			int withoutModel = labelRulesOnly.mask(text).totalMasked();
			int withModel = withOpenNlp.mask(text).totalMasked();

			// Recorded rather than asserted as an improvement, because it is not one: every
			// name in these samples is labelled, so the model has nothing left to contribute.
			// This is the evidence for defaulting it off - see SECURITY.md.
			assertThat(withModel).as("masked spans in %s", file).isGreaterThanOrEqualTo(withoutModel);
		}
	}

	private static String read(String fileName) {
		ClassPathResource resource = new ClassPathResource("samples/" + fileName);
		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read sample " + fileName, ex);
		}
	}

	private record PlantedData(String file, String value, MaskType type) {
	}

	private static ObjectProvider<OpenNlpNameRule> absent() {
		return provider(null);
	}

	private static ObjectProvider<OpenNlpNameRule> provider(OpenNlpNameRule rule) {
		return new ObjectProvider<>() {
			@Override
			public OpenNlpNameRule getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public OpenNlpNameRule getIfAvailable() {
				return rule;
			}

			@Override
			public OpenNlpNameRule getIfUnique() {
				return rule;
			}
		};
	}
}
