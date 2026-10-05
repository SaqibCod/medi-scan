package dev.saq.mediscan.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import dev.saq.mediscan.config.MediScanProperties;

/**
 * Per-IP rate limiting with Bucket4j.
 *
 * <p>Phase 1 limits session creation to 20 per hour per IP (contract section 1.6). Upload and
 * chat limits, which key on the user id when signed in, arrive with the endpoints they
 * protect.
 *
 * <p>Buckets are held in memory. Losing them on restart is acceptable and intended
 * ({@code docs/dataflow.md} section 9.3): the limit exists to stop casual abuse of a free
 * demo, not to be an accounting record, and one instance runs at a time.
 */
@Component
public class IpRateLimiter {

	private final Map<String, Bucket> sessionBuckets = new ConcurrentHashMap<>();
	private final int sessionsPerHour;

	public IpRateLimiter(MediScanProperties properties) {
		this.sessionsPerHour = properties.ratelimit().sessionsPerHour();
	}

	/**
	 * Consumes one token for session creation from this request's IP.
	 *
	 * @throws RateLimitExceededException with the seconds to wait, when the bucket is empty
	 */
	public void checkSessionCreation(HttpServletRequest request) {
		String ip = clientIp(request);
		Bucket bucket = sessionBuckets.computeIfAbsent(ip, key -> newSessionBucket());

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw new RateLimitExceededException(retryAfterSeconds(probe));
		}
	}

	private Bucket newSessionBucket() {
		// Greedy refill trickles tokens back continuously rather than releasing the whole
		// allowance on the hour, so a client that waits briefly gets in instead of every
		// blocked client retrying at the same instant.
		return Bucket.builder()
				.addLimit(limit -> limit
						.capacity(sessionsPerHour)
						.refillGreedy(sessionsPerHour, Duration.ofHours(1)))
				.build();
	}

	/** At least 1, so a client is never told to retry immediately. */
	private long retryAfterSeconds(ConsumptionProbe probe) {
		long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
		return Math.max(seconds, 1);
	}

	/**
	 * The caller's IP.
	 *
	 * <p>{@code server.forward-headers-strategy=framework} makes Spring apply
	 * {@code X-Forwarded-For} from Caddy before this runs, so {@code getRemoteAddr} already
	 * returns the real client address. Reading the header directly here would be worse: a
	 * client can set it themselves, which would let anyone bypass the limit by sending a
	 * different value each time.
	 */
	private String clientIp(HttpServletRequest request) {
		String ip = request.getRemoteAddr();
		return ip != null ? ip : "unknown";
	}
}
