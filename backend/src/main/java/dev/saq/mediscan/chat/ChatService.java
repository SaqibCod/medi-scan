package dev.saq.mediscan.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.saq.mediscan.config.ApiException;
import dev.saq.mediscan.config.ErrorCode;
import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.llm.DailyCapGuard;
import dev.saq.mediscan.llm.LlmException;
import dev.saq.mediscan.llm.LlmGateway;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.PromptTemplates;
import dev.saq.mediscan.report.BiomarkerEntity;
import dev.saq.mediscan.report.BiomarkerRepository;
import dev.saq.mediscan.report.ReportEntity;
import dev.saq.mediscan.report.ReportRepository;
import dev.saq.mediscan.report.ReportStatus;
import dev.saq.mediscan.report.ReportTextEntity;
import dev.saq.mediscan.report.ReportTextRepository;
import dev.saq.mediscan.session.OwnerRef;
import dev.saq.mediscan.stats.StatsRecorder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Answers one question about one report ({@code LLD} 6.1).
 *
 * <p>The order of operations is the design. Everything that can refuse the request runs
 * <em>before</em> the response is committed, so a refusal is ordinary JSON Problem Details as
 * the contract requires (4.4). Once the first byte is written there is no going back, and
 * failures become SSE {@code error} events instead.
 *
 * <p>Nothing here is stored. The question, the history, the prompt and the answer exist in
 * memory for as long as the stream takes and are then gone ({@code CLAUDE.md} rule 9), and
 * none of them is ever logged (rule 19). What is logged is the report id, the finish reason,
 * counts and timings.
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	private final ReportRepository reports;
	private final BiomarkerRepository biomarkers;
	private final ReportTextRepository texts;
	private final TypedTextMasker masker;
	private final ChunkRetriever retriever;
	private final PromptBuilder promptBuilder;
	private final PromptTemplates prompts;
	private final LlmGateway gateway;
	private final ChatCache cache;
	private final DailyCapGuard capGuard;
	private final StatsRecorder stats;
	private final Clock clock;
	private final MediScanProperties.Chat settings;

	public ChatService(ReportRepository reports, BiomarkerRepository biomarkers,
			ReportTextRepository texts, TypedTextMasker masker, ChunkRetriever retriever,
			PromptBuilder promptBuilder, PromptTemplates prompts, LlmGateway gateway,
			ChatCache cache, DailyCapGuard capGuard, StatsRecorder stats, Clock clock,
			MediScanProperties properties) {

		this.reports = reports;
		this.biomarkers = biomarkers;
		this.texts = texts;
		this.masker = masker;
		this.retriever = retriever;
		this.promptBuilder = promptBuilder;
		this.prompts = prompts;
		this.gateway = gateway;
		this.cache = cache;
		this.capGuard = capGuard;
		this.stats = stats;
		this.clock = clock;
		this.settings = properties.chat();
	}

	/**
	 * Loads the report, if it belongs to this caller and is ready to be asked about.
	 *
	 * <p>Read-only and owner-scoped, like every report query ({@code CLAUDE.md} rule 7).
	 * Someone else's report is a {@code 404}, identical to one that never existed.
	 *
	 * @throws ApiException {@code 404 REPORT_NOT_FOUND} or {@code 409 REPORT_NOT_READY}
	 */
	@Transactional(readOnly = true)
	public ChatReport loadReport(UUID reportId, OwnerRef owner) {
		Instant now = Instant.now(clock);

		ReportEntity report = (switch (owner) {
			case OwnerRef.Guest guest -> reports.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> reports.findOwnedByUser(reportId, user.id(), now);
		}).orElseThrow(() -> new ApiException(ErrorCode.REPORT_NOT_FOUND,
				"That report does not exist."));

		if (report.getStatus() != ReportStatus.DONE) {
			// There is nothing to ask about yet. A separate code from 404 because the report
			// is the caller's and will become answerable - the client can poll and retry.
			throw new ApiException(ErrorCode.REPORT_NOT_READY,
					"This report is still being processed.");
		}

		List<BiomarkerEntity> values = switch (owner) {
			case OwnerRef.Guest guest -> biomarkers.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> biomarkers.findOwnedByUser(reportId, user.id(), now);
		};
		ReportTextEntity text = (switch (owner) {
			case OwnerRef.Guest guest -> texts.findOwnedBySession(reportId, guest.id(), now);
			case OwnerRef.User user -> texts.findOwnedByUser(reportId, user.id(), now);
		}).orElseThrow(() -> new ApiException(ErrorCode.REPORT_NOT_FOUND,
				"That report does not exist."));

		return new ChatReport(reportId, text.getMaskedText(),
				values.stream().map(ChatService::toBiomarker).toList(),
				report.getCollectedOn());
	}

	/** Refuses early when the budget is already gone, so no stream is opened for nothing. */
	public void requireCapacity() {
		if (capGuard.isCapReached()) {
			throw new ApiException(ErrorCode.CAPACITY,
					"The demo has reached today's AI limit. Chat opens again at midnight UTC.");
		}
	}

	/** Masks the question and every history turn before either reaches the model or the cache. */
	public Masked mask(ChatRequest request) {
		List<ChatTurn> history = new ArrayList<>(request.history().size());
		for (ChatRequest.Turn turn : request.history()) {
			history.add(new ChatTurn(turn.role(), masker.mask(turn.content())));
		}
		return new Masked(masker.mask(request.trimmedQuestion()), List.copyOf(history));
	}

	/** A cached answer for this exact first question, if there is one. */
	public java.util.Optional<ChatCache.CachedAnswer> cached(UUID reportId, Masked masked) {
		return cache.find(reportId, masked.question(), masked.history());
	}

	/**
	 * Replays a cached answer.
	 *
	 * <p><strong>Still through the guard.</strong> It passed when it was first made, and
	 * checking again costs nothing - rule 18 has no exceptions, and "it's only the cache" is
	 * named in that rule as not being a reason.
	 */
	public void writeCached(ChatStreamWriter writer, ChatReport report,
			ChatCache.CachedAnswer answer) {

		stats.recordChatCacheHit();
		AnswerGuard guard = new AnswerGuard(allowedFor(report));

		for (String sentence : guard.accept(answer.text())) {
			if (!writer.token(sentence)) {
				return;
			}
		}
		for (String sentence : guard.finish()) {
			if (!writer.token(sentence)) {
				return;
			}
		}

		if (guard.stopped()) {
			// Only reachable if the allowed set changed under a cached answer, which should be
			// impossible for one report. Counted rather than assumed away.
			stats.recordChatNumberStop();
			writer.token(AnswerGuard.STOP_NOTICE);
			writer.sources(true, answer.sources());
			writer.done("COMPLETE");
			return;
		}

		writer.sources(true, answer.sources());
		writer.done(answer.finishReason());
		recordOutcome(answer.finishReason());
		log.info("chat answer reportId={} cached=true finishReason={}", report.id(),
				answer.finishReason());
	}

	/**
	 * Asks the model and writes the answer, sentence by sentence.
	 *
	 * <p>Blocks the calling thread until the stream finishes, which is what the async MVC
	 * worker is for. Everything that can fail here is after the response was committed, so it
	 * becomes an {@code error} event rather than a status code.
	 */
	public void answer(ChatStreamWriter writer, ChatReport report, Masked masked) {
		long startedAt = System.nanoTime();

		List<PageChunk> chunks;
		try {
			chunks = retriever.retrieve(masked.question(), report);
		}
		catch (EmbeddingGateway.EmbeddingUnavailableException ex) {
			// Retrieval failed, which is survivable: the report alone is still a basis for an
			// answer, and refusing outright would be a worse outcome than a narrower one.
			log.warn("chat retrieval failed reportId={} detail={}", report.id(), ex.getMessage());
			chunks = List.of();
		}

		String prompt = promptBuilder.build(report, chunks, masked.history(), masked.question());
		TagParser tagParser = new TagParser();
		AnswerGuard guard = new AnswerGuard(allowedFor(report));
		StringBuilder answer = new StringBuilder();

		Flux<dev.saq.mediscan.llm.LlmStreamChunk> stream = gateway.streamChat(
				prompts.systemPrompt(LlmPurpose.CHAT), prompt,
				settings.temperature(), settings.maxOutputTokens());

		Outcome outcome = consume(writer, stream, tagParser, guard, answer);

		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
		finish(writer, report, masked, chunks, tagParser, guard, answer, outcome, elapsedMs);
	}

	/**
	 * Drains the model stream into guarded sentences.
	 *
	 * <p>Cancels the subscription as soon as the guard stops or a write fails. A stream nobody
	 * is reading still costs tokens, and an answer the guard has rejected must not go on being
	 * generated.
	 */
	private Outcome consume(ChatStreamWriter writer,
			Flux<dev.saq.mediscan.llm.LlmStreamChunk> stream, TagParser tagParser,
			AnswerGuard guard, StringBuilder answer) {

		java.util.concurrent.atomic.AtomicReference<Throwable> failure =
				new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.atomic.AtomicBoolean readerGone =
				new java.util.concurrent.atomic.AtomicBoolean();

		Disposable subscription = stream.subscribe(
				chunk -> {
					for (String sentence : guard.accept(tagParser.accept(chunk.text()))) {
						answer.append(sentence);
						if (!writer.token(sentence)) {
							readerGone.set(true);
							return;
						}
					}
				},
				error -> {
					failure.set(error);
					finished.countDown();
				},
				finished::countDown);

		try {
			// The stream timeout is the emitter's job as well; this is the worker's own bound
			// so a provider that never completes cannot hold the thread for ever.
			if (!finished.await(settings.streamTimeout().toMillis() + 5_000,
					java.util.concurrent.TimeUnit.MILLISECONDS)) {
				subscription.dispose();
				return new Outcome(null, true, false);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			subscription.dispose();
			return new Outcome(null, true, false);
		}
		finally {
			if (guard.stopped() || readerGone.get()) {
				subscription.dispose();
			}
		}

		return new Outcome(failure.get(), false, readerGone.get());
	}

	/** Sends whatever the stream's ending calls for, records stats, and caches if allowed. */
	private void finish(ChatStreamWriter writer, ChatReport report, Masked masked,
			List<PageChunk> chunks, TagParser tagParser, AnswerGuard guard,
			StringBuilder answer, Outcome outcome, long elapsedMs) {

		if (outcome.readerGone()) {
			log.info("chat answer reportId={} outcome=disconnected ms={}", report.id(), elapsedMs);
			return;
		}

		if (outcome.failure() != null || outcome.timedOut()) {
			String code = outcome.failure() instanceof LlmException.CapacityLlmException
					? "CAPACITY" : "LLM_UNAVAILABLE";
			writer.error(code, messageFor(code));
			log.warn("chat answer reportId={} outcome=failed code={} ms={}",
					report.id(), code, elapsedMs);
			return;
		}

		// Whatever the tag parser and guard were still holding.
		for (String sentence : guard.accept(tagParser.finish())) {
			answer.append(sentence);
			writer.token(sentence);
		}
		for (String sentence : guard.finish()) {
			answer.append(sentence);
			writer.token(sentence);
		}

		List<ChatCache.SourcePage> sources = sourcesOf(chunks);

		if (guard.stopped()) {
			stats.recordChatNumberStop();
			writer.token(AnswerGuard.STOP_NOTICE);
			writer.sources(true, sources);
			writer.done("COMPLETE");
			// Deliberately not cached: the answer is incomplete, and caching it would serve
			// the same truncation to the next reader without the model ever being asked again.
			log.info("chat answer reportId={} finishReason=COMPLETE guardStopped=true ms={}",
					report.id(), elapsedMs);
			return;
		}

		String finishReason = tagParser.decision().finishReason();
		writer.sources(true, sources);
		writer.done(finishReason);

		recordOutcome(finishReason);
		if (masked.history().isEmpty() && !answer.isEmpty()) {
			cache.put(report.id(), masked.question(),
					new ChatCache.CachedAnswer(answer.toString(), finishReason, sources));
		}

		log.info("chat answer reportId={} finishReason={} chunks={} tagMissing={} ms={}",
				report.id(), finishReason, chunks.size(), tagParser.tagWasMissing(), elapsedMs);
	}

	private void recordOutcome(String finishReason) {
		stats.recordChatAnswer();
		switch (finishReason) {
			case "DECLINED" -> stats.recordChatDeclined();
			case "NOT_IN_CONTEXT" -> stats.recordChatNotInContext();
			default -> {
				// COMPLETE needs no second counter; chat_answers already counted it.
			}
		}
	}

	private static String messageFor(String code) {
		return "CAPACITY".equals(code)
				? "Today's AI limit was reached partway through this answer."
				: "The AI service didn't respond. Your question is kept - try asking again.";
	}

	private static List<ChatCache.SourcePage> sourcesOf(List<PageChunk> chunks) {
		List<ChatCache.SourcePage> sources = new ArrayList<>();
		for (PageChunk chunk : chunks) {
			boolean alreadyListed = sources.stream()
					.anyMatch(page -> page.slug().equals(chunk.slug()));
			if (!alreadyListed) {
				sources.add(new ChatCache.SourcePage(chunk.slug(), chunk.title(), chunk.heading()));
			}
		}
		return sources;
	}

	private static AllowedNumbers allowedFor(ChatReport report) {
		return AllowedNumbers.of(report.printedValues(), report.maskedText(), report.counts(),
				report.collectedOn());
	}

	private static ChatReport.ChatBiomarker toBiomarker(BiomarkerEntity entity) {
		return new ChatReport.ChatBiomarker(entity.getTestName(), entity.getRawValue(),
				entity.getUnit(), entity.getReferenceRangeText(),
				entity.getFlag() == null ? "UNKNOWN" : entity.getFlag().name(),
				entity.getBiomarkerSlug());
	}

	/** The question and history after masking. */
	public record Masked(String question, List<ChatTurn> history) {

		/** Hides both. */
		@Override
		public String toString() {
			return "Masked[questionChars=" + question.length() + ", turns=" + history.size() + "]";
		}
	}

	/**
	 * How the model stream ended.
	 *
	 * @param failure the error it ended with, or null
	 * @param timedOut whether the worker gave up waiting
	 * @param readerGone whether a write failed, meaning the client disconnected
	 */
	private record Outcome(Throwable failure, boolean timedOut, boolean readerGone) {
	}
}
