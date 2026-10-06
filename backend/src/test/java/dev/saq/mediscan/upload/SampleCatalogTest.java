package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * The bundled samples, and the promises the contract makes about them.
 *
 * <p>The startup consistency check means the catalogue cannot load at all if a file is
 * missing or its row count drifted from the index - so the context starting is itself part of
 * what these tests assert.
 */
class SampleCatalogTest extends PostgresTestBase {

	@Autowired
	SampleCatalog catalog;

	@Test
	@DisplayName("loads exactly the three samples the contract documents")
	void loadsContractSamples() {
		assertThat(catalog.all())
				.extracting(Sample::id)
				.containsExactly("lipid-panel", "cbc", "thyroid-panel");
	}

	@Test
	@DisplayName("marker counts match the contract's published numbers")
	void markerCountsMatchContract() {
		// These are published through GET /api/samples, so they are part of the API.
		assertThat(catalog.find("lipid-panel").orElseThrow().markerCount()).isEqualTo(5);
		assertThat(catalog.find("cbc").orElseThrow().markerCount()).isEqualTo(12);
		assertThat(catalog.find("thyroid-panel").orElseThrow().markerCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("an unknown id is empty, not an exception")
	void unknownIdIsEmpty() {
		assertThat(catalog.find("no-such-sample")).isEmpty();
		assertThat(catalog.find("")).isEmpty();
	}

	@ParameterizedTest(name = "{0} carries personal data for masking to find")
	@ValueSource(strings = { "lipid-panel", "cbc", "thyroid-panel" })
	@DisplayName("every sample exercises masking on a demo run")
	void samplesCarryPersonalData(String id) {
		String text = catalog.find(id).orElseThrow().text();

		// LLD 9.3: each sample carries a fake name, id, phone number and birth date, so a
		// plain demo run exercises the masking rules rather than only the tests doing so.
		assertThat(text).containsIgnoringCase("Patient Name:");
		assertThat(text).containsPattern("(?i)(DOB|D\\.O\\.B\\.|Date of Birth)");
		assertThat(text).containsPattern("\\(555\\) 555-01\\d\\d");
		assertThat(text).containsPattern("(?i)(Patient ID|MRN|Medical Record No):");
		assertThat(text).containsIgnoringCase("Collected:");
	}

	@ParameterizedTest(name = "{0} is labelled synthetic")
	@ValueSource(strings = { "lipid-panel", "cbc", "thyroid-panel" })
	@DisplayName("every sample says in its own text that it is synthetic")
	void samplesSayTheyAreSynthetic(String id) {
		// A reader who sees only the extracted text should be able to tell this is not a real
		// person's report.
		assertThat(catalog.find(id).orElseThrow().text()).containsIgnoringCase("synthetic");
	}

	@Test
	@DisplayName("the samples cover HIGH, LOW, NORMAL and a qualitative value")
	void samplesCoverEveryFlag() {
		// LLD 9.3 asks for all four, because a demo that only ever shows NORMAL rows proves
		// nothing about the flag logic, and the qualitative row is the one that must render
		// as text rather than on a range bar.
		String lipid = catalog.find("lipid-panel").orElseThrow().text();
		assertThat(lipid).contains("238").contains("<200");   // above range
		assertThat(lipid).contains("38").contains(">40");      // below range
		assertThat(lipid).contains("140").contains("<150");    // inside range

		String thyroid = catalog.find("thyroid-panel").orElseThrow().text();
		assertThat(thyroid).contains("Negative");              // qualitative
	}

	@Test
	@DisplayName("Sample.toString hides the report text")
	void toStringHidesText() {
		Sample sample = catalog.find("lipid-panel").orElseThrow();

		String printed = sample.toString();

		assertThat(printed).contains("lipid-panel").contains("textLength");
		// The specific thing that must never reach a log line.
		assertThat(printed).doesNotContain("Jane Q. Roe");
		assertThat(printed).doesNotContain("A1234567");
		assertThat(printed).doesNotContain("238");
	}

	@Test
	@DisplayName("the response DTO carries no report text")
	void responseDtoOmitsText() {
		List<SampleResponse> responses = catalog.all().stream().map(SampleResponse::from).toList();

		assertThat(responses).hasSize(3);
		// Guards the reason SampleResponse exists at all: Sample holds the text, this does not.
		assertThat(SampleResponse.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("id", "title", "description", "markerCount");
	}
}
