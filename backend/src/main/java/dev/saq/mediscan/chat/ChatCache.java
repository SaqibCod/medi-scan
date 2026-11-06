package dev.saq.mediscan.chat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.saq.mediscan.config.MediScanProperties;

/**
 * Remembers answers to first questions ({@code LLD} C8, 8).
 *
 * <p>Most repeats are the four suggested questions on the report page, asked by whoever opens
 * it. A hit costs no model call and no embedding call, which on a free tier is the difference
 * between a demo that stays up all day and one that stops at lunchtime.
 *
 * <p><strong>Only questions with no history.</strong> A follow-up depends on the conversation
 * it follows, so caching one would mean serving an answer built for a different exchange. The
 * key cannot express that, and it should not try.
 *
 * <p><strong>The key always starts with the report id</strong>, so an entry can never cross
 * reports or owners - two people asking the same question about different reports get
 * different answers, and the same question about someone else's report is a different key
 * entirely. The prompt version and the model are in the key too: a prompt edit or a model
 * change makes previous answers stale rather than silently authoritative.
 *
 * <p><strong>Memory only.</strong> Nothing here is ever written to the database
 * ({@code CLAUDE.md} rule 9), and it is dropped on restart, on report deletion, and by its own
 * expiry.
 *
 * <p>A cached answer is still checked by the number guard on its way out. It was checked when
 * it was first made, and checking again costs nothing - it removes a whole class of "how did
 * that get cached" bug, and rule 18 says the guard has no exceptions.
 */
@Component
public class ChatCache implements dev.saq.mediscan.report.ReportViewService.DeletionListener {

	private final Cache<String, CachedAnswer> cache;
	private final String promptVersion;
	private final String model;

	public ChatCache(MediScanProperties properties) {
		this.cache = Caffeine.newBuilder()
				.maximumSize(properties.chat().cacheMaxEntries())
				.expireAfterWrite(properties.chat().cacheTtl())
				.build();
		this.promptVersion = "chat-v1";
		this.model = properties.chat().model();
	}

	/**
	 * A previous answer to this exact question about this report, if there is one.
	 *
	 * @param history the conversation so far; a non-empty one never hits
	 */
	public Optional<CachedAnswer> find(UUID reportId, String question, List<ChatTurn> history) {
		if (!history.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(cache.getIfPresent(keyFor(reportId, question)));
	}

	/** Stores an answer. Only ever called for a question that arrived with no history. */
	public void put(UUID reportId, String question, CachedAnswer answer) {
		cache.put(keyFor(reportId, question), answer);
	}

	/**
	 * Drops every answer for one report.
	 *
	 * <p>Called when the report is deleted. Without it, a reader who deleted their report
	 * could still be served sentences from it by asking the same question again - which would
	 * make "delete" a lie.
	 */
	@Override
	public void reportDeleted(UUID reportId) {
		String prefix = reportId + "|";
		cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
	}

	/** Entries held. For tests and the startup log; never the contents. */
	public long size() {
		cache.cleanUp();
		return cache.estimatedSize();
	}

	/**
	 * Report id, then the normalised question, then what produced the answer.
	 *
	 * <p>Normalising - lowercase, collapsed whitespace, trimmed - catches "Why is my LDL
	 * flagged?" against "why is my ldl flagged?", which is the difference between a chip being
	 * clicked and the same words being typed.
	 */
	private String keyFor(UUID reportId, String question) {
		String normalized = question.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		return reportId + "|" + normalized + "|" + promptVersion + "|" + model;
	}

	/**
	 * One stored answer.
	 *
	 * @param text the whole answer, which is replayed in sentence-sized pieces
	 * @param finishReason the contract value the {@code done} event carried
	 * @param sources the pages the answer used, for the {@code sources} event
	 */
	public record CachedAnswer(String text, String finishReason, List<SourcePage> sources) {

		public CachedAnswer {
			sources = List.copyOf(sources);
		}

		/** Hides the answer text, which is writing about someone's report. */
		@Override
		public String toString() {
			return "CachedAnswer[chars=" + text.length() + ", finishReason=" + finishReason
					+ ", sources=" + sources.size() + "]";
		}
	}

	/** A page an answer drew on, as the {@code sources} event sends it (contract 4.4). */
	public record SourcePage(String slug, String title, String heading) {
	}
}
