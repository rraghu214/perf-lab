package dev.crucible.perflab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /actuator/env shows Crucible's tunables and masks everything else.
 *
 * <p>The negative cases carry the weight: {@code show-values=always} is on, so
 * this function is the only thing standing between the endpoint and every
 * secret in the environment. Constructed directly, like VersionEndpointTests:
 * no Spring context, no database.
 */
class EnvSanitizerConfigTests {

    private final SanitizingFunction function = new EnvSanitizerConfig().onlyCrucibleTunablesAreVisible();

    private Object shown(String key, String value) {
        return function.apply(new SanitizableData(null, key, value)).getValue();
    }

    @Test
    void aTunableCrucibleReadsBackIsShown() {
        assertThat(shown("spring.datasource.hikari.maximum-pool-size", "20")).isEqualTo("20");
        assertThat(shown("server.tomcat.threads.max", "200")).isEqualTo("200");
        assertThat(shown("perflab.jvm.heap-max-mb", "1024")).isEqualTo("1024");
    }

    @Test
    void theDatadogApiKeyIsMasked() {
        // The reason the allowlist exists: with values on, this would otherwise show.
        assertThat(shown("management.datadog.metrics.export.api-key", "real-key"))
                .isEqualTo(SanitizableData.SANITIZED_VALUE);
    }

    @Test
    void credentialsAndUnlistedPropertiesAreMasked() {
        assertThat(shown("spring.datasource.password", "pw")).isEqualTo(SanitizableData.SANITIZED_VALUE);
        assertThat(shown("spring.datasource.url", "jdbc:postgresql://db/x")).isEqualTo(SanitizableData.SANITIZED_VALUE);
        assertThat(shown("DATADOG_API_KEY", "real-key")).isEqualTo(SanitizableData.SANITIZED_VALUE);
        assertThat(shown("PATH", "/usr/bin")).isEqualTo(SanitizableData.SANITIZED_VALUE);
    }

    @Test
    void aCredentialLookingNameStaysMaskedEvenIfListed() {
        // Guards a careless edit to VISIBLE: listing a secret must not publish it.
        assertThat(EnvSanitizerConfig.visible("spring.datasource.hikari.password")).isFalse();
        assertThat(EnvSanitizerConfig.visible("some.api-key")).isFalse();
    }

    @Test
    void everyListedTunableIsVisible() {
        // None of today's thirteen names looks like a credential; if one ever
        // does, this fails and someone decides deliberately.
        for (String key : EnvSanitizerConfig.VISIBLE) {
            assertThat(EnvSanitizerConfig.visible(key)).as(key).isTrue();
        }
    }
}
