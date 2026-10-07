package dev.saq.mediscan.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import dev.saq.mediscan.analysis.ModelExtraction;
import dev.saq.mediscan.llm.FakeLlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.support.PostgresTestBase;
import dev.saq.mediscan.support.ReportFixtures;

/**
 * A full job queue is refused, not absorbed ({@code LLD} 17.2 item 8).
 *
 * <p>The behaviour under test is what happens on a 2 GB box under load. An unbounded queue
 * would turn a traffic spike into an out-of-memory kill; a caller told {@code 429 BUSY} can
 * retry, and - crucially - is left with nothing to clean up. A {@code PENDING} report nobody
 * will ever process is worse than no report, because the client would poll it until it expired.
 *
 * <p>One worker and a queue of two, so the limit is reached in three requests rather than
 * twenty-three. The fake provider blocks on a latch, which holds the worker in a known state
 * without sleeping.
 */
@TestPropertySource(properties = {
		"mediscan.jobs.workers=1",
		"mediscan.jobs.queue-capacity=2",
		"mediscan.ratelimit.uploads-per-hour=100",
		"mediscan.llm.daily-cap=500",
})
class QueueFullTest extends PostgresTestBase {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	FakeLlmProvider provider;

	@Autowired
	ReportRepository reports;

	@Autowired
	ReportFixtures fixtures;

	@Autowired
	TempFileStore tempFiles;

	@Autowired
	JdbcClient jdbc;

	private String token;
	private CountDownLatch release;

	@BeforeEach
	void reset() {
		fixtures.clear();
		provider.reset();
		jdbc.sql("delete from llm_usage").update();
		token = fixtures.sessionToken();

		release = new CountDownLatch(1);

		// Every job blocks until released, so the worker stays occupied and the queue stays
		// full. Queued rather than set as a standing response, because alwaysRespondWith
		// clears the queue - which silently removed the block and let every job run straight
		// through the first time this was written.
		for (int i = 0; i < 6; i++) {
			provider.queue(LlmPurpose.EXTRACT, new FakeLlmProvider.Script.Block(release,
					new ModelExtraction(List.of(), null)));
		}
	}

	@AfterEach
	void releaseWorkers() throws Exception {
		release.countDown();

		// Let the blocked jobs finish before the next test, and confirm nothing was left on
		// disk once they had.
		Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
			try (var files = Files.list(tempFiles.directory())) {
				return files.findAny().isEmpty();
			}
		});
	}

	@Test
	@DisplayName("the request past the queue limit is 429 BUSY with Retry-After")
	void refusesWhenQueueIsFull() throws Exception {
		fillQueue();

		mockMvc.perform(sample())
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("BUSY"))
				// Contract section 1.6: a 429 always says how long to wait.
				.andExpect(header().string("Retry-After", "30"));
	}

	@Test
	@DisplayName("a BUSY refusal creates no report row")
	void busyRefusalCreatesNoReport() throws Exception {
		fillQueue();
		long before = reports.count();

		mockMvc.perform(sample()).andExpect(status().isTooManyRequests());

		// Contract section 4.1: the report is not created. The caller's retry starts from
		// nothing rather than from a report that will never be picked up.
		assertThat(reports.count()).isEqualTo(before);
	}

	@Test
	@DisplayName("a BUSY refusal leaves no temp file")
	void busyRefusalLeavesNoTempFile() throws Exception {
		fillQueue();

		// A PDF upload, so there would be a temp file to leak if the capacity check happened
		// after it was written rather than before.
		mockMvc.perform(pdfUpload()).andExpect(status().isTooManyRequests());

		try (var files = Files.list(tempFiles.directory())) {
			assertThat(files.toList())
					.as("a refused upload must leave nothing on disk")
					.isEmpty();
		}
	}

	@Test
	@DisplayName("uploads are accepted again once the queue drains")
	void acceptsAgainAfterDraining() throws Exception {
		fillQueue();
		mockMvc.perform(sample()).andExpect(status().isTooManyRequests());

		release.countDown();

		// The pool drains and the server recovers on its own - BUSY is back-pressure, not an
		// outage.
		Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> {
			try {
				return mockMvc.perform(sample()).andReturn().getResponse().getStatus() == 202;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
	}

	/** Fills the single worker and both queue slots. */
	private void fillQueue() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(sample()).andExpect(status().isAccepted());
		}

		// The capacity check reads the queue's remaining capacity, so wait until the worker
		// has actually taken its job and the queue holds the other two.
		Awaitility.await().atMost(Duration.ofSeconds(10))
				.until(() -> reports.count() >= 3);
	}

	private RequestBuilder sample() {
		return post("/api/reports")
				.header("X-Session-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sampleId": "lipid-panel", "consent": true}""");
	}

	private RequestBuilder pdfUpload() {
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.multipart("/api/reports")
				.file(new org.springframework.mock.web.MockMultipartFile("file", "report.pdf",
						MediaType.APPLICATION_PDF_VALUE,
						dev.saq.mediscan.support.TestPdfs.withText(List.of(
								"Total Cholesterol 238 mg/dL <200 H",
								"HDL Cholesterol 38 mg/dL >40 L",
								"Triglycerides 140 mg/dL <150"))))
				.param("consent", "true")
				.header("X-Session-Token", token);
	}
}
