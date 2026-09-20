package dev.crucible.perflab;

/**
 * Response body for GET /api/version.
 *
 * <p>{@code commit} is what lets Crucible prove the target is running the build
 * it just deployed, which DESIGN.md 19.6 requires before any measurement may
 * begin. It is deliberately nullable: a jar built without
 * {@code -Dperflab.commit=<sha>} reports {@code null} rather than a placeholder,
 * because a plausible-looking wrong sha is worse than an absent one -- Crucible
 * refuses to measure on null, and would happily measure the wrong build on a
 * fake.
 */
public class VersionInfo {

    private String app;
    private String version;
    private int poolSize;
    private String commit;

    public VersionInfo() {
    }

    public VersionInfo(String app, String version, int poolSize, String commit) {
        this.app = app;
        this.version = version;
        this.poolSize = poolSize;
        this.commit = commit;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public String getCommit() {
        return commit;
    }

    public void setCommit(String commit) {
        this.commit = commit;
    }
}
