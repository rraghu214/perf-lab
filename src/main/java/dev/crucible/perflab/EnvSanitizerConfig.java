package dev.crucible.perflab;

import java.util.Locale;
import java.util.Set;

import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which /actuator/env values may be shown: the tunables Crucible changes, and
 * nothing else.
 *
 * <p>Crucible proves a change is in force by reading the property back from
 * /actuator/env (Crucible DESIGN.md 19.6, rung 2). Spring Boot 3 masks every
 * value by default, so the read-back saw "******" and could never confirm
 * anything. {@code management.endpoint.env.show-values=always} turns values on;
 * this function then masks every value except an allowlist. Allowlist, never
 * blocklist: a property nobody thought about stays hidden.
 *
 * <p>The list mirrors {@code allowed_properties} in Crucible's
 * {@code config/profiles/spring-boot.yaml}. A property added there but not here
 * stays masked, and Crucible reports its read-back as "unavailable" rather than
 * failing, so the cost of the two drifting apart is a weaker proof, not a leak.
 *
 * <p>A name that looks like a credential stays masked even if it is listed, so
 * a careless edit to the list cannot publish a password or an API key.
 */
@Configuration
public class EnvSanitizerConfig {

    static final Set<String> VISIBLE = Set.of(
            "spring.datasource.hikari.maximum-pool-size",
            "spring.datasource.hikari.minimum-idle",
            "spring.datasource.hikari.connection-timeout",
            "server.tomcat.threads.max",
            "server.tomcat.threads.min-spare",
            "spring.task.execution.pool.core-size",
            "spring.task.execution.pool.max-size",
            "spring.task.execution.pool.queue-capacity",
            "spring.jpa.properties.hibernate.jdbc.batch_size",
            "spring.jpa.properties.hibernate.default_batch_fetch_size",
            "perflab.cache.enabled",
            "perflab.downstream.timeout-ms",
            "perflab.jvm.heap-max-mb");

    private static final String[] SENSITIVE = {"password", "secret", "key", "token", "credential"};

    static boolean visible(String key) {
        if (key == null || !VISIBLE.contains(key)) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : SENSITIVE) {
            if (lower.contains(word)) {
                return false;
            }
        }
        return true;
    }

    @Bean
    SanitizingFunction onlyCrucibleTunablesAreVisible() {
        return data -> visible(data.getKey()) ? data : data.withValue(SanitizableData.SANITIZED_VALUE);
    }
}
