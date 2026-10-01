package com.ademola.esm.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's single source of "now".
 *
 * <p>Business code asks this injected {@link Clock} for the time instead of calling
 * {@code Instant.now()} directly, so tests can pin or advance time. That becomes essential for the
 * SLA engine (Phase 2), where "80% of the resolution time has elapsed" must be testable without
 * waiting hours.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
