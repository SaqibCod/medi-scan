package dev.saq.mediscan.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock.
 *
 * <p>A bean rather than {@code Instant.now()} at each call site, because almost everything
 * with a deadline in this system is awkward to test otherwise: session and report expiry,
 * the daily LLM cap's UTC day boundary, the retention sweep's cutoffs, and whether a
 * collection date is in the future. A test fixes the clock instead of sleeping
 * ({@code backend/CLAUDE.md}: "inject a {@code java.time.Clock} and use UTC").
 *
 * <p>UTC, not the system zone. The production box, a developer laptop and CI would otherwise
 * disagree about which day a report was processed on, and {@code llm_usage} is keyed by day.
 *
 * <p>{@link ConditionalOnMissingBean} so an integration test can supply a fixed clock by
 * declaring its own bean, without excluding this configuration.
 */
@Configuration
public class ClockConfig {

	@Bean
	@ConditionalOnMissingBean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
