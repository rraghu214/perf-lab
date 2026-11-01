package dev.crucible.perflab;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/metrics reports cumulative seconds while the Datadog registry is active.
 *
 * <p>Drafted 1 November 2026, for operator review. Before MetricsEndpointConfig, this
 * passed or failed depending on JVM start order (see that class): about one start in
 * three answered from the Datadog registry, in milliseconds and over the last step.
 */
@SpringBootTest(properties = {
        "management.datadog.metrics.export.enabled=true",
        "management.datadog.metrics.export.api-key=test-not-a-key",
        "management.datadog.metrics.export.uri=http://127.0.0.1:9",
})
class MetricsEndpointConfigTests {

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private MetricsEndpoint endpoint;

    @Test
    void bothRegistriesAreActiveSoTheTestMeansSomething() {
        assertThat(registry).isInstanceOf(CompositeMeterRegistry.class);
        assertThat(((CompositeMeterRegistry) registry).getRegistries())
                .hasAtLeastOneElementOfType(PrometheusMeterRegistry.class)
                .hasAtLeastOneElementOfType(DatadogMeterRegistry.class);
    }

    @Test
    void aTimerIsReportedInSecondsAndCountedSinceStart() {
        Timer timer = Timer.builder("perflab.test.timer").register(registry);
        timer.record(Duration.ofMillis(1500));
        timer.record(Duration.ofMillis(500));

        MetricsEndpoint.MetricDescriptor metric = endpoint.metric("perflab.test.timer", null);
        Map<String, Double> values = metric.getMeasurements().stream()
                .collect(Collectors.toMap(m -> m.getStatistic().name(), MetricsEndpoint.Sample::getValue));

        assertThat(metric.getBaseUnit()).isEqualTo("seconds");
        assertThat(values.get("COUNT")).isEqualTo(2.0);
        assertThat(values.get("TOTAL_TIME")).isEqualTo(2.0);
        assertThat(values.get("MAX")).isEqualTo(1.5);
    }
}
