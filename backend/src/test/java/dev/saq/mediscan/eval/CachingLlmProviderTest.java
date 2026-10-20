package dev.saq.mediscan.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.saq.mediscan.llm.LlmProvider;
import dev.saq.mediscan.llm.LlmPurpose;
import dev.saq.mediscan.llm.LlmRequest;
import dev.saq.mediscan.llm.LlmResult;

/** Hand-made delegate, per {@code docs/phase-3-lld.md} section 9, item 3. */
class CachingLlmProviderTest {

	@Test
	void anUncachedCallReachesTheDelegateAndIsCached(@TempDir Path dir) {
		var delegate = new CountingDelegate();
		var caching = new CachingLlmProvider(delegate, dir, false, 0);

		String value = caching.structured(request("one"), String.class).value();

		assertThat(value).isEqualTo("response-to-one");
		assertThat(delegate.callCount.get()).isEqualTo(1);
		assertThat(caching.realCallCount()).isEqualTo(1);
		assertThat(caching.cacheHitCount()).isZero();
	}

	@Test
	void aRepeatedCallWithTheSamePromptHitsTheCacheInstead(@TempDir Path dir) {
		var delegate = new CountingDelegate();
		var caching = new CachingLlmProvider(delegate, dir, false, 0);

		caching.structured(request("one"), String.class);
		String second = caching.structured(request("one"), String.class).value();

		assertThat(second).isEqualTo("response-to-one");
		assertThat(delegate.callCount.get()).isEqualTo(1);
		assertThat(caching.realCallCount()).isEqualTo(1);
		assertThat(caching.cacheHitCount()).isEqualTo(1);
	}

	@Test
	void aDifferentUserContentIsADifferentCacheKey(@TempDir Path dir) {
		var delegate = new CountingDelegate();
		var caching = new CachingLlmProvider(delegate, dir, false, 0);

		caching.structured(request("one"), String.class);
		caching.structured(request("two"), String.class);

		assertThat(delegate.callCount.get()).isEqualTo(2);
		assertThat(caching.cacheHitCount()).isZero();
	}

	@Test
	void refreshCacheIgnoresAnExistingEntry(@TempDir Path dir) {
		var delegate = new CountingDelegate();
		var caching = new CachingLlmProvider(delegate, dir, true, 0);

		caching.structured(request("one"), String.class);
		caching.structured(request("one"), String.class);

		assertThat(delegate.callCount.get()).isEqualTo(2);
		assertThat(caching.cacheHitCount()).isZero();
	}

	@Test
	void aCacheHitDoesNotPaceTheCall(@TempDir Path dir) {
		var delegate = new CountingDelegate();
		// A large delay would make this test slow if a cache hit paced too.
		var caching = new CachingLlmProvider(delegate, dir, false, 2000);

		caching.structured(request("one"), String.class);
		long start = System.nanoTime();
		caching.structured(request("one"), String.class);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMs).isLessThan(500);
	}

	private static LlmRequest request(String userContent) {
		return new LlmRequest(LlmPurpose.EXTRACT, "system prompt", userContent, 0.0, 100);
	}

	/** Returns a value derived from the request, so a cache hit is distinguishable from a miss. */
	private static final class CountingDelegate implements LlmProvider {

		final AtomicInteger callCount = new AtomicInteger();

		@Override
		public String id() {
			return "fake";
		}

		@Override
		public String model() {
			return "fake-model";
		}

		@Override
		public <T> LlmResult<T> structured(LlmRequest request, Class<T> outputType) {
			callCount.incrementAndGet();
			@SuppressWarnings("unchecked")
			T value = (T) ("response-to-" + request.userContent());
			return new LlmResult<>(value, 10, 10, 5L);
		}
	}
}
