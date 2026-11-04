package dev.saq.mediscan.chat;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.saq.mediscan.config.CurrentOwner;
import dev.saq.mediscan.config.MediScanProperties;
import dev.saq.mediscan.ratelimit.IpRateLimiter;
import dev.saq.mediscan.session.OwnerRef;
import dev.saq.mediscan.stats.StatsRecorder;

/**
 * {@code POST /api/reports/{id}/chat} (contract 4.4).
 *
 * <p><strong>Every check happens before the emitter is returned.</strong> That is the whole
 * reason this method is shaped the way it is. Validation, the rate limit, the owner lookup,
 * the report's status and the daily cap all run while the response is still uncommitted, so a
 * refusal travels as ordinary JSON Problem Details through the existing advice - which is what
 * the contract promises and what the client's error handling switches on. The moment an
 * {@link SseEmitter} is returned, the status line is 200 and the only way left to report a
 * problem is an {@code error} event.
 *
 * <p>Thin, like every controller here: it authenticates, validates, resolves one service call
 * and hands back the emitter. The answering happens on a worker thread it owns, because the
 * container's request thread must be released for async processing to mean anything.
 */
@RestController
@RequestMapping("/api/reports")
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private final ChatService service;
	private final IpRateLimiter rateLimiter;
	private final StatsRecorder stats;
	private final MediScanProperties.Chat settings;

	/**
	 * Workers for answering, and a scheduler for keep-alive pings.
	 *
	 * <p>Virtual threads: an answer is almost entirely waiting on the model, so a platform
	 * thread per concurrent reader would be the wrong trade on a 2 GB box. Deliberately not
	 * the report job pool, which has two workers and exists to bound CPU-heavy work - chat
	 * waiting on the network must never queue behind a PDF being parsed, or take a slot from
	 * one.
	 */
	private final ExecutorService workers =
			Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("chat-", 0).factory());

	private final ScheduledExecutorService pings =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "chat-ping");
				thread.setDaemon(true);
				return thread;
			});

	public ChatController(ChatService service, IpRateLimiter rateLimiter, StatsRecorder stats,
			MediScanProperties properties) {

		this.service = service;
		this.rateLimiter = rateLimiter;
		this.stats = stats;
		this.settings = properties.chat();
	}

	@PostMapping(path = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chat(@PathVariable UUID id,
			@Valid @RequestBody ChatRequest request,
			@CurrentOwner OwnerRef owner,
			Authentication authentication,
			HttpServletRequest httpRequest) {

		// --- before the response is committed ---------------------------------
		try {
			rateLimiter.checkChat(bucketKey(authentication, httpRequest));
		}
		catch (RuntimeException ex) {
			stats.recordRateLimitRejection();
			throw ex;
		}

		ChatReport report = service.loadReport(id, owner);
		ChatService.Masked masked = service.mask(request);

		var cached = service.cached(id, masked);
		if (cached.isEmpty()) {
			// Only when the model will actually be called. A cache hit costs nothing, so
			// refusing one because the budget is gone would withhold an answer this
			// application already has.
			service.requireCapacity();
		}

		// --- committed from here ---------------------------------------------
		UUID answerId = UUID.randomUUID();
		SseEmitter emitter = new SseEmitter(settings.streamTimeout().toMillis());
		ChatStreamWriter writer = new ChatStreamWriter(emitter);

		writer.meta(answerId, cached.isPresent());

		ScheduledFuture<?> ping = pings.scheduleAtFixedRate(writer::ping,
				settings.pingInterval().toMillis(), settings.pingInterval().toMillis(),
				TimeUnit.MILLISECONDS);
		emitter.onCompletion(() -> ping.cancel(false));
		emitter.onTimeout(() -> ping.cancel(false));
		emitter.onError(error -> ping.cancel(false));

		workers.execute(() -> {
			try {
				cached.ifPresentOrElse(
						answer -> service.writeCached(writer, report, answer),
						() -> service.answer(writer, report, masked));
			}
			catch (RuntimeException ex) {
				// Nothing more specific survived to here, so the client gets the generic code.
				// The class name only: an exception message can carry report content.
				log.error("chat failed reportId={} error={}", id, ex.getClass().getSimpleName());
				writer.error("INTERNAL_ERROR", "Something went wrong writing this answer.");
			}
			finally {
				ping.cancel(false);
				complete(emitter);
			}
		});

		return emitter;
	}

	/**
	 * Completes the emitter, tolerating one that the container already finished.
	 *
	 * <p>A reader who disconnects mid-answer leaves an emitter the container has completed
	 * with an error of its own; calling {@code complete} on it throws, and that exception
	 * would otherwise be the last thing in the log for an entirely ordinary event.
	 */
	private static void complete(SseEmitter emitter) {
		try {
			emitter.complete();
		}
		catch (RuntimeException ex) {
			log.debug("emitter already completed: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * {@code user:<id>} for a signed-in caller, {@code ip:<address>} otherwise.
	 *
	 * <p>Keying by user matters on a shared address: two people behind one office NAT each get
	 * their own thirty questions rather than competing for one allowance. A guest has no stable
	 * identity to key on - a session token would be useless, since a caller can mint fresh ones
	 * freely - so the address is the best available.
	 */
	private String bucketKey(Authentication authentication, HttpServletRequest request) {
		if (authentication instanceof JwtAuthenticationToken bearer) {
			return "user:" + bearer.getToken().getSubject();
		}
		return rateLimiter.ipBucketKey(request);
	}
}
