package dev.saq.mediscan.upload;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.saq.mediscan.support.PostgresTestBase;

/**
 * {@code GET /api/samples} against contract section 3.
 *
 * <p>Checked field by field rather than as a whole-body comparison, so a failure names the
 * field that drifted.
 */
class SampleControllerTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Test
	@DisplayName("returns the contract's three samples with every documented field")
	void returnsContractShape() throws Exception {
		mockMvc.perform(get("/api/samples"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.length()").value(3))

				.andExpect(jsonPath("$[0].id").value("lipid-panel"))
				.andExpect(jsonPath("$[0].title").value("Lipid panel"))
				.andExpect(jsonPath("$[0].description").value("Cholesterol and triglycerides, with high LDL."))
				.andExpect(jsonPath("$[0].markerCount").value(5))

				.andExpect(jsonPath("$[1].id").value("cbc"))
				.andExpect(jsonPath("$[1].title").value("Complete blood count"))
				.andExpect(jsonPath("$[1].description").value("Red and white blood cells, all in range."))
				.andExpect(jsonPath("$[1].markerCount").value(12))

				.andExpect(jsonPath("$[2].id").value("thyroid-panel"))
				.andExpect(jsonPath("$[2].title").value("Thyroid panel"))
				.andExpect(jsonPath("$[2].description").value("TSH and free T4, with low TSH."))
				.andExpect(jsonPath("$[2].markerCount").value(3));
	}

	@Test
	@DisplayName("never returns the sample report text")
	void omitsReportText() throws Exception {
		// The catalogue holds the text; this endpoint is a list of labels. If the text ever
		// appeared here it would be a privacy bug on a real sample set, so it is asserted
		// rather than assumed.
		mockMvc.perform(get("/api/samples"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("Patient Name"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("Jane Q. Roe"))))
				.andExpect(jsonPath("$[0].text").doesNotExist());
	}

	@Test
	@DisplayName("needs no credential")
	void isPublic() throws Exception {
		// No X-Session-Token and no bearer token: a first-time visitor must be able to see
		// the samples before any session exists (CLAUDE.md rule 11).
		mockMvc.perform(get("/api/samples")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("is cacheable for an hour")
	void isCacheable() throws Exception {
		mockMvc.perform(get("/api/samples"))
				.andExpect(header().string("Cache-Control", containsString("max-age=3600")))
				.andExpect(header().string("Cache-Control", containsString("public")));
	}
}
