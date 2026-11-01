package dev.crucible.perflab;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * /actuator/metrics reads the Prometheus registry, never whichever registry comes first.
 *
 * <p>With both the Prometheus and the Datadog registry on the classpath, Spring Boot
 * wraps them in one CompositeMeterRegistry, and its MetricsEndpoint answers from the
 * FIRST child registry that has the meter. Micrometer keeps those children in an
 * IdentityHashMap-backed set, so the order changes from one JVM start to the next.
 * When Datadog came first, /actuator/metrics reported its step values: milliseconds,
 * counted over the last 10 seconds only, while Crucible reads seconds over the whole
 * run. Found 1 November 2026 in about a third of Crucible's captures (an HTTP max of
 * 3,691,754 "ms" where Datadog itself said 4,594).
 *
 * <p>The Prometheus registry is cumulative and in seconds, and /actuator/prometheus
 * already reads it, so both endpoints now report the same numbers on every start.
 * Spring Boot's own MetricsEndpoint bean is conditional on there being none, so this
 * one replaces it. Datadog export is unaffected: it still receives every meter.
 */
@Configuration
public class MetricsEndpointConfig {

    @Bean
    MetricsEndpoint metricsEndpoint(PrometheusMeterRegistry prometheus) {
        return new MetricsEndpoint(prometheus);
    }
}
