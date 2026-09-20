package dev.crucible.perflab;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/version must be able to prove which build is running.
 *
 * <p>DESIGN.md 19.6 forbids starting a measurement until the target confirms the
 * deployed commit. The whole value of that rule is in the negative cases: a
 * target that reports a placeholder sha is worse than one that reports nothing,
 * because Crucible would accept the placeholder and measure the wrong build.
 * These tests are therefore mostly about what must NOT come back.
 *
 * <p>Constructed directly rather than through {@code @SpringBootTest}: the real
 * BuildProperties bean carries whatever sha the local build happened to inject,
 * which is not something a test can assert against.
 */
class VersionEndpointTests {

    private static PerfController controllerWith(String commitValue) {
        PerfController controller = new PerfController(
                null, null, null, null, null, null, null, null,
                providerFor(commitValue));
        ReflectionTestUtils.setField(controller, "appName", "perf-lab");
        ReflectionTestUtils.setField(controller, "version", "0.2.0");
        ReflectionTestUtils.setField(controller, "poolSize", 20);
        return controller;
    }

    /** An ObjectProvider holding one BuildProperties, or none at all for null. */
    private static ObjectProvider<BuildProperties> providerFor(String commitValue) {
        if (commitValue == null) {
            return new StubProvider(null);
        }
        Properties properties = new Properties();
        properties.put("group", "dev.crucible");
        properties.put("artifact", "perf-lab");
        properties.put("version", "0.2.0");
        properties.put("commit", commitValue);
        return new StubProvider(new BuildProperties(properties));
    }

    @Test
    void anInjectedShaIsReportedVerbatim() {
        // The one positive case. Crucible compares this against the sha it
        // pushed, so any transformation here (shortening, lowercasing) would
        // have to be mirrored on the Python side -- so there is none.
        VersionInfo info = controllerWith("9f2c1ab7de4455aa0031bb77cc2210ee44556677").version();

        assertThat(info.getCommit()).isEqualTo("9f2c1ab7de4455aa0031bb77cc2210ee44556677");
        assertThat(info.getPoolSize()).isEqualTo(20);
    }

    @Test
    void theDefaultBuildReportsNullRatherThanTheWordUnknown() {
        // pom.xml defaults perflab.commit to "unknown" because Spring Boot's
        // build-info goal rejects an empty additional property. That default must
        // not escape as a string: Crucible's same_commit() would compare "unknown"
        // against a real sha and correctly refuse, but a future caller doing a
        // truthiness check would see a non-empty string and conclude the target
        // had proven itself.
        assertThat(controllerWith("unknown").version().getCommit()).isNull();
        assertThat(controllerWith("UNKNOWN").version().getCommit()).isNull();
    }

    @Test
    void aBlankOrAbsentBuildInfoReportsNullAndDoesNotThrow() {
        // Started from an IDE, build-info.properties does not exist and the bean
        // is absent. The app must still serve /api/version: a target that 500s
        // here looks unreachable to the watchdog, which is a different and much
        // noisier failure than "cannot prove my commit".
        assertThat(controllerWith(null).version().getCommit()).isNull();
        assertThat(controllerWith("   ").version().getCommit()).isNull();
    }

    @Test
    void theOtherFieldsSurviveTheChange() {
        // /api/version already reported app, version and poolSize, and the K1
        // automation refuses to start load unless poolSize matches. Adding commit
        // must not disturb what that check reads.
        VersionInfo info = controllerWith("abc1234def5678").version();

        assertThat(info.getApp()).isEqualTo("perf-lab");
        assertThat(info.getVersion()).isEqualTo("0.2.0");
        assertThat(info.getPoolSize()).isEqualTo(20);
    }

    /** Minimal ObjectProvider: only getIfAvailable() is exercised. */
    private record StubProvider(BuildProperties value) implements ObjectProvider<BuildProperties> {
        @Override
        public BuildProperties getObject() {
            return value;
        }

        @Override
        public BuildProperties getObject(Object... args) {
            return value;
        }

        @Override
        public BuildProperties getIfAvailable() {
            return value;
        }

        @Override
        public BuildProperties getIfUnique() {
            return value;
        }
    }
}
