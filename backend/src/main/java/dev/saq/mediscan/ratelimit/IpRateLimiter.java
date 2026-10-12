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
 * <p>Session creation and report creation have their own limits and their own buckets
 * (contract section 1.6). The chat limit, which keys on the user id when signed in, arrives
 * with the endpoint it protects.
 *
 * <p>Buckets are held in memory. Losing them on restart is acceptable and intended
 * ({@code docs/dataflow.md} section 9.3): the limit exists to stop casual abuse of a free
 * demo, not to be an accounting record, and one instance runs at a time.
 */
@Component
public class IpRateLimiter {

	private final Map<String, Bucket> sessionBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> uploadBuckets = new ConcurrentHashMap<>();

	// Separate maps, not one keyed by endpoint, so a burst of auto sign-ins in new tabs
	// cannot use up the allowance a reload needs for its refresh (LLD 9).
	private final Map<String, Bucket> signInBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> refreshBuckets = new ConcurrentHashMap<>();

	private final int sessionsPerHour;
	private final int uploadsPerHour;
	private final int authPerMinute;

	public IpRateLimiter(MediScanProperties properties) {
		this.sessionsPerHour = properties.ratelimit().sessionsPerHour();
		this.uploadsPerHour = properties.ratelimit().uploadsPerHour();
		this.authPerMinute = properties.ratelimit().authPerMinute();
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

	/**
	 * Consumes one token for report creation from this request's IP.
	 *
	 * <p>A separate bucket from session creation, not a shared one. The two limits exist for
	 * different reasons - sessions are cheap and only need abuse protection, while an upload
	 * spends LLM budget - and sharing a bucket would let a burst of session creation lock a
	 * legitimate user out of uploading.
	 *
	 * @throws RateLimitExceededException with the seconds to wait, when the bucket is empty
	 */
	/**
	 * Uploads, keyed by whoever is calling.
	 *
	 * <p>The key is {@code user:<id>} for a signed-in caller and {@code ip:<address>}
	 * otherwise (LLD 8). Keying by user matters for shared addresses: two people behind one
	 * office NAT, or one household, each get their own ten uploads instead of competing for a
	 * single allowance. An IP key remains for guests, who have no stable identity to key on -
	 * a guest session token would be no use, because a caller can mint fresh ones freely.
	 */
	public void checkUpload(HttpServletRequest request, String bucketKey) {
		Bucket bucket = uploadBuckets.computeIfAbsent(bucketKey, key -> newUploadBucket());

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw new RateLimitExceededException(retryAfterSeconds(probe));
		}
	}

	/** The IP-keyed bucket name, for callers with no account. */
	public String ipBucketKey(HttpServletRequest request) {
		return "ip:" + clientIp(request);
	}

	/** Ten Google sign-in attempts per minute per IP (contract section 1.6). */
	public void checkGoogleSignIn(HttpServletRequest request) {
		consumeAuth(signInBuckets, request);
	}

	/** Ten token refreshes per minute per IP, in a bucket of their own. */
	public void checkTokenRefresh(HttpServletRequest request) {
		consumeAuth(refreshBuckets, request);
	}

	private void consumeAuth(Map<String, Bucket> buckets, HttpServletRequest request) {
		String ip = clientIp(request);
		Bucket bucket = buckets.computeIfAbsent(ip, key -> newAuthBucket());

		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			throw new RateLimitExceededException(retryAfterSeconds(probe));
		}
	}

	private Bucket newAuthBucket() {
		return Bucket.builder()
				.addLimit(limit -> limit
						.capacity(authPerMinute)
						.refillGreedy(authPerMinute, Duration.ofMinutes(1)))
				.build();
	}

	private Bucket newUploadBucket() {
		return Bucket.builder()
				.addLimit(limit -> limit
						.capacity(uploadsPerHour)
						.refillGreedy(uploadsPerHour, Duration.ofHours(1)))
				.build();
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
