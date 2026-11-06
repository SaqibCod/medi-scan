package dev.saq.mediscan.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bundled synthetic sample reports, loaded from the classpath at startup.
 *
 * <p>Samples are the demo's front door: a visitor who does not want to upload anything still
 * gets to see the whole pipeline. They are also the fixture every masking and pipeline test
 * runs against, which is why each one carries a fake name, id, phone number and birth date -
 * so masking is exercised on every demo run rather than only in tests.
 *
 * <p><strong>Consistency check.</strong> The constructor fails startup if a listed file is
 * missing or if its row count disagrees with {@code markerCount} in the index. That matters
 * because {@code markerCount} is published through {@code GET /api/samples} and is asserted
 * against the contract: without the check, editing a sample file and forgetting the index
 * would ship a number that quietly contradicts the API. Failing at startup makes the drift
 * impossible to miss, and it is a few milliseconds on three small files.
 */
@Component
public class SampleCatalog {

	private static final Logger log = LoggerFactory.getLogger(SampleCatalog.class);

	private static final String DIRECTORY = "samples/";
	private static final String INDEX = DIRECTORY + "index.json";

	/** The line that opens and closes a sample's results block. */
	private static final String ROW_DELIMITER = "-----";

	/** Insertion-ordered, so {@code GET /api/samples} returns them in the index's order. */
	private final Map<String, Sample> samplesById;

	public SampleCatalog(JsonMapper jsonMapper) {
		this.samplesById = load(jsonMapper);
		log.info("Loaded {} sample reports", samplesById.size());
	}

	/** Every sample, in index order. */
	public List<Sample> all() {
		return List.copyOf(samplesById.values());
	}

	/**
	 * One sample by id.
	 *
	 * <p>Empty is what the upload validator turns into {@code 404 SAMPLE_NOT_FOUND}.
	 */
	public Optional<Sample> find(String id) {
		return Optional.ofNullable(samplesById.get(id));
	}

	private static Map<String, Sample> load(JsonMapper jsonMapper) {
		List<IndexEntry> entries = readIndex(jsonMapper);
		if (entries.isEmpty()) {
			throw new IllegalStateException(INDEX + " lists no samples");
		}

		Map<String, Sample> loaded = new LinkedHashMap<>();
		for (IndexEntry entry : entries) {
			entry.validate();

			String text = readText(entry.file());
			int actualRows = countResultRows(text);
			if (actualRows != entry.markerCount()) {
				throw new IllegalStateException(("sample '%s' lists markerCount=%d in " + INDEX
						+ " but %s contains %d result rows; GET /api/samples publishes this number, "
						+ "so update whichever is wrong")
								.formatted(entry.id(), entry.markerCount(), entry.file(), actualRows));
			}

			Sample sample = new Sample(entry.id(), entry.title(), entry.description(),
					entry.markerCount(), text);
			if (loaded.put(entry.id(), sample) != null) {
				throw new IllegalStateException("duplicate sample id '" + entry.id() + "' in " + INDEX);
			}
		}
		// unmodifiableMap, not Map.copyOf: the latter returns an unordered map, and the
		// index's order is the order GET /api/samples publishes.
		return Collections.unmodifiableMap(loaded);
	}

	private static List<IndexEntry> readIndex(JsonMapper jsonMapper) {
		ClassPathResource resource = new ClassPathResource(INDEX);
		if (!resource.exists()) {
			throw new IllegalStateException("missing " + INDEX + " on the classpath");
		}
		try (InputStream in = resource.getInputStream()) {
			return jsonMapper.readValue(in, new TypeReference<List<IndexEntry>>() {
			});
		}
		catch (IOException ex) {
			// The index is ours and is on the classpath, so a failure here is a packaging
			// bug, not a runtime condition worth recovering from.
			throw new IllegalStateException("could not read " + INDEX, ex);
		}
	}

	private static String readText(String fileName) {
		ClassPathResource resource = new ClassPathResource(DIRECTORY + fileName);
		if (!resource.exists()) {
			throw new IllegalStateException(
					INDEX + " lists '" + fileName + "', which is not on the classpath");
		}
		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read sample file '" + fileName + "'", ex);
		}
	}

	/**
	 * Counts the result rows in a sample.
	 *
	 * <p>Rows are the non-blank lines between the first and second {@code -----} delimiter
	 * line that follow the column header. These are three files this repository authors, so a
	 * fixed layout is a reasonable thing to require - and requiring it is what makes the
	 * count exact rather than a guess. A sample without the delimited block fails startup,
	 * which is the intended outcome: it would also be a sample the extraction prompt reads
	 * badly.
	 *
	 * <p>Deliberately not {@code ResultRowDetector}: that class answers "might this line hold
	 * a value worth protecting from masking", which is tuned to over-protect. Here the
	 * question is the exact row count of a known file, and borrowing a fuzzy rule for it
	 * would make the check report drift that is not there.
	 */
	private static int countResultRows(String text) {
		List<String> lines = text.lines().toList();

		int firstDelimiter = indexOfDelimiter(lines, 0);
		if (firstDelimiter < 0) {
			throw new IllegalStateException(
					"sample has no '-----' results block; see the bundled samples for the layout");
		}
		// The header row sits between the first two delimiters; rows follow the second.
		int secondDelimiter = indexOfDelimiter(lines, firstDelimiter + 1);
		int thirdDelimiter = secondDelimiter < 0 ? -1 : indexOfDelimiter(lines, secondDelimiter + 1);
		if (thirdDelimiter < 0) {
			throw new IllegalStateException(
					"sample results block is not closed by a third '-----' line");
		}

		int rows = 0;
		for (String line : lines.subList(secondDelimiter + 1, thirdDelimiter)) {
			if (!line.isBlank()) {
				rows++;
			}
		}
		return rows;
	}

	private static int indexOfDelimiter(List<String> lines, int from) {
		for (int i = from; i < lines.size(); i++) {
			if (lines.get(i).strip().startsWith(ROW_DELIMITER)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * One entry in {@code samples/index.json}.
	 *
	 * <p>{@code file} is in the index rather than derived from {@code id} so a sample can be
	 * renamed in the API without renaming the file, and so a missing file is a startup
	 * failure naming both.
	 */
	record IndexEntry(String id, String file, String title, String description,
			int markerCount) {

		void validate() {
			require(id, "id");
			require(file, "file");
			require(title, "title");
			require(description, "description");
			if (markerCount <= 0) {
				throw new IllegalStateException(
						"sample '" + id + "' in " + INDEX + " has markerCount " + markerCount);
			}
		}

		private void require(String value, String field) {
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("a sample in " + INDEX + " is missing '" + field + "'");
			}
		}
	}
}
