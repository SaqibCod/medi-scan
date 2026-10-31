package dev.saq.mediscan.extract;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted {@link OcrEngine}.
 *
 * <p>Exists because the logic worth testing in this phase cannot be provoked with a real OCR
 * run. The low-confidence rule turns on a value being read at, say, 62% - and there is no way
 * to ask Tesseract to be 62% sure about a specific digit. Scripting the confidence is the only
 * way to test the rule that keeps a misread number from getting a flag.
 *
 * <p>Also records what it was asked to do, including when, which is how the single-permit
 * guarantee is tested: two concurrent reports must not have overlapping OCR calls.
 */
class FakeOcrEngine implements OcrEngine {

	/** Pages handed out in order; the last one repeats once the queue runs dry. */
	private final Deque<OcrPage> pages = new ArrayDeque<>();

	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private final AtomicInteger concurrent = new AtomicInteger();
	private final AtomicInteger maxConcurrent = new AtomicInteger();

	private OcrPage lastPage = OcrPage.EMPTY;
	private OcrFailedException.Reason failWith;
	private Duration delay = Duration.ZERO;

	/** Queues a page built from text, with every word at {@code confidence}. */
	FakeOcrEngine returning(String text, double confidence) {
		pages.add(page(text, confidence));
		return this;
	}

	/** Queues a prebuilt page, for the tests that need per-word confidences. */
	FakeOcrEngine returning(OcrPage page) {
		pages.add(page);
		return this;
	}

	/** Every later call fails this way. */
	FakeOcrEngine failing(OcrFailedException.Reason reason) {
		this.failWith = reason;
		return this;
	}

	/** Makes each call take this long, so the gate's serialisation is observable. */
	FakeOcrEngine takingAtLeast(Duration delay) {
		this.delay = delay;
		return this;
	}

	@Override
	public OcrPage recognize(Path image, Duration timeout) {
		int running = concurrent.incrementAndGet();
		maxConcurrent.accumulateAndGet(running, Math::max);
		long startedAt = System.nanoTime();

		try {
			if (!delay.isZero()) {
				try {
					Thread.sleep(delay.toMillis());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new OcrFailedException(OcrFailedException.Reason.INTERRUPTED);
				}
			}

			if (failWith != null) {
				throw new OcrFailedException(failWith);
			}

			if (!pages.isEmpty()) {
				lastPage = pages.poll();
			}
			return lastPage;
		}
		finally {
			calls.add(new Call(image, timeout, startedAt, System.nanoTime()));
			concurrent.decrementAndGet();
		}
	}

	int callCount() {
		return calls.size();
	}

	List<Call> calls() {
		return List.copyOf(calls);
	}

	/** The most calls that were ever in flight at once. One, if the gate works. */
	int maxConcurrentCalls() {
		return maxConcurrent.get();
	}

	/**
	 * Builds a single-line-per-input-line page with a uniform confidence.
	 *
	 * <p>Word offsets are computed the same way {@link TsvLineBuilder} computes them, so a test
	 * using this gets offsets consistent with the real thing.
	 */
	static OcrPage page(String text, double confidence) {
		List<OcrLine> lines = new ArrayList<>();
		double total = 0;
		int wordCount = 0;

		for (String rawLine : text.split("\n", -1)) {
			List<OcrWord> words = new ArrayList<>();
			int cursor = 0;
			while (cursor < rawLine.length()) {
				if (rawLine.charAt(cursor) == ' ') {
					cursor++;
					continue;
				}
				int end = cursor;
				while (end < rawLine.length() && rawLine.charAt(end) != ' ') {
					end++;
				}
				String word = rawLine.substring(cursor, end);
				words.add(new OcrWord(word, confidence, cursor, end, cursor * 10, word.length() * 10));
				total += confidence;
				wordCount++;
				cursor = end;
			}
			lines.add(new OcrLine(rawLine, words));
		}

		return new OcrPage(lines, wordCount == 0 ? 0 : total / wordCount);
	}

	/**
	 * A page where one word reads badly.
	 *
	 * <p>The shape the low-confidence rule exists for: a value Tesseract was unsure about,
	 * surrounded by text it was confident in.
	 */
	static OcrPage pageWithLowConfidenceWord(String text, String word, double lowConfidence,
			double otherConfidence) {

		OcrPage base = page(text, otherConfidence);
		List<OcrLine> lines = new ArrayList<>();
		double total = 0;
		int count = 0;

		for (OcrLine line : base.lines()) {
			List<OcrWord> words = new ArrayList<>();
			for (OcrWord original : line.words()) {
				double confidence = original.text().equals(word) ? lowConfidence : otherConfidence;
				words.add(new OcrWord(original.text(), confidence, original.start(),
						original.end(), original.left(), original.width()));
				total += confidence;
				count++;
			}
			lines.add(new OcrLine(line.text(), words));
		}

		return new OcrPage(lines, count == 0 ? 0 : total / count);
	}

	/**
	 * One recorded call.
	 *
	 * <p>{@code toString()} keeps the path out: in a real run it is a temp file, and file names
	 * are not logged.
	 */
	record Call(Path image, Duration timeout, long startedAtNanos, long endedAtNanos) {

		boolean overlaps(Call other) {
			return startedAtNanos < other.endedAtNanos && other.startedAtNanos < endedAtNanos;
		}

		@Override
		public String toString() {
			return "Call[timeout=" + timeout + ", durationMs="
					+ ((endedAtNanos - startedAtNanos) / 1_000_000) + "]";
		}
	}
}
