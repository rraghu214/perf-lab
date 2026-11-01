# PerfLab — Product & NFR Reference

PerfLab is a Spring Boot service built to be the target of
[Crucible](https://github.com/rraghu214/crucible), an autonomous performance
engineer. Every endpoint fails in one specific, well-understood way so that an
agent diagnosing it has a ground truth to be measured against.

---

## Endpoints and cause families

| Endpoint | Cause family | What is wrong | Default fix |
|---|---|---|---|
| `/api/fast` | — | Baseline: no dependency, always fast | — |
| `/api/db` | `connection_pool_exhaustion` | Holds a pooled connection across 50 ms of simulated DB work; pool=2 saturates at ~4 concurrent users | Raise `spring.datasource.hikari.maximum-pool-size` |
| `/api/async` | `thread_pool_saturation` | Queues work onto a fixed-size executor; thread pool=2 saturates under concurrent load | Raise `spring.task.execution.pool.max-size` |
| `/api/churn` | `gc_pressure` | Allocates and retains large byte arrays in a loop; GC overhead climbs under sustained load | Raise heap via `-Xmx`, or reduce allocation rate in code |
| `/api/orders` | `inefficient_query` | N+1 query: one SELECT per order item, with batch fetching disabled | Enable `spring.jpa.properties.hibernate.default_batch_fetch_size` |
| `/api/catalog` | `cache_miss` | Reads through to the database on every request when the cache is disabled | Set `perf-lab.cache.enabled=true` |
| `/api/downstream` | `downstream_latency` | Calls httpbin `/delay/2` synchronously; latency is bounded by the downstream | Reduce httpbin delay or add a timeout+fallback |
| `/api/ledger` | `lock_contention` | A `synchronized` block over shared state serialises all concurrent requests | Remove or narrow the lock scope |
| `/api/payload` | `payload_serialization` | Returns thousands of rows per request; serialisation dominates response time | Add pagination (`?limit=`, `?page=`) |

---

## Stack

| Component | Version / Notes |
|---|---|
| Spring Boot | 3.x |
| JDK | 21 (LTS) |
| Database | Postgres 15 (lab profile) · H2 in-memory (default) |
| Cache | Redis 7 (lab profile) · disabled (default) |
| Metrics | Micrometer → Actuator (`/actuator/metrics`) + Prometheus (`/actuator/prometheus`) |
| Tracing | Micrometer Tracing → Jaeger (lab profile, OTLP gRPC) |
| Build | Maven Wrapper (`./mvnw`) |
| Deployment | Post-receive hook on Box A restarts the JVM from `perftest_sandbox` branch |

---

## NFR — Non-Functional Requirements

These govern PerfLab itself as a test fixture, not the service it is designed to make slow.

### NFR-01 · Measurement repeatability

**Requirement:** Two consecutive baseline runs under identical load must produce p99 latency values within ±5% of each other.

**Rationale:** An agent cannot distinguish a genuine improvement from noise unless the fixture is stable. The 2.08% noise floor measured on Oracle Ashburn (`docs/K1_CLOUD_RESULT.md`) sets the practical bound; ±5% gives headroom for JVM warmup variance.

**Verification:** Run `LocustRunner` with `users=50`, `duration=300s`, twice in succession with no config change. Both p99 values must lie within the same ±5% band.

---

### NFR-02 · Single-cause isolation

**Requirement:** Each endpoint must exhibit exactly one cause family under load. No endpoint may be ambiguously diagnosable as two different cause families simultaneously.

**Rationale:** Ground truth requires an unambiguous answer. A fixture diagnosable as either `gc_pressure` or `connection_pool_exhaustion` teaches nothing about the agent's ability to distinguish them.

**Verification:** Run each endpoint in isolation at its designed load level. The Crucible agent must identify the declared cause family in ≥90% of replay runs across all fixture variants.

---

### NFR-03 · Fix verifiability

**Requirement:** The fix for each cause family must be readable back as a metric or a config endpoint value from a running JVM, within 5 seconds of deployment, without a code change.

**Rationale:** Crucible's deploy-verification ladder requires the target to confirm its own configuration (§19.6 of DESIGN.md). A fix that cannot be read back forces the run to record as `UNVERIFIED`.

**Verification:** After applying the canonical fix for each endpoint, call `GET /actuator/env` or the relevant Actuator metric and confirm the new value is present.

---

### NFR-04 · Health endpoint availability

**Requirement:** `GET /actuator/health` must return HTTP 200 and `{"status":"UP"}` within 2 seconds at all load levels, including during saturation of `/api/db`.

**Rationale:** Crucible's watchdog polls `/actuator/health` to confirm the service is reachable. A saturated connection pool must not starve the health endpoint.

**Verification:** Run `/api/db` at pool-saturating load (50 users, pool=2). Concurrently poll `/actuator/health` at 1 Hz for 30 seconds. All responses must be 200 within 2 s.

---

### NFR-05 · Metrics cold-start

**Requirement:** All Micrometer meters for an endpoint must be initialised within 10 requests of first invocation. No meter relevant to diagnosis may be missing after a warm-up of 10 requests.

**Rationale:** `hikaricp_connections_acquire_seconds_*` and `hikaricp_connections_active` do not exist on a cold JVM. A collector reading them before warm-up will find nothing, which is a valid but confusing state. Ten requests is sufficient for Micrometer to register the meter.

**Verification:** Restart the JVM, send exactly 10 requests to each endpoint, then read `GET /actuator/metrics`. All declared meters for that endpoint must be present.

---

### NFR-06 · Startup time

**Requirement:** The JVM must reach `{"status":"UP"}` within 60 seconds of process start.

**Rationale:** Crucible's deploy-verification step waits for health before measuring. A slow startup eats into the 90-second deploy-verification budget and causes a spurious `UNVERIFIED` result.

**Verification:** Measure time from `./mvnw spring-boot:run` to first successful `GET /actuator/health`. Must be ≤60 s on the Oracle Ashburn box.

---

### NFR-07 · Sandbox branch safety

**Requirement:** All Crucible-driven configuration changes are applied via `perftest_sandbox` branch. `main` is never directly modified by Crucible.

**Rationale:** DESIGN.md §19.1a enforces this in Crucible's `DeployTarget`. The post-receive hook on Box A must also enforce it: only pushes to `perftest_sandbox` trigger a restart.

**Verification:** Attempt a direct push to `main` from Crucible's workspace — the post-receive hook must reject it or not trigger a restart.

---

### NFR-08 · Secrets and credentials

**Requirement:** No database password, Redis auth token, or API key may appear in any HTTP response, including `/actuator/env`.

**Rationale:** Actuator's `/env` endpoint returns all resolved properties. Micrometer redaction must mask datasource passwords before the endpoint responds. Crucible's credential-redaction allowlist runs before the snapshot is handed to the model.

**Verification:** Call `GET /actuator/env` with Actuator exposed. Confirm that `spring.datasource.password` and any `*.secret` or `*.key` properties are masked (`"******"`).

---

## Tunable properties (Crucible's allowlist)

These are the only properties Crucible's agent is permitted to change. Bounds are
declared in `profile.yaml` in the Crucible workspace; changing a bound requires an
operator edit, never an agent action.

| Property | Default | Crucible's allowed range | Cause family |
|---|---|---|---|
| `spring.datasource.hikari.maximum-pool-size` | 2 | 2 – 50 | `connection_pool_exhaustion` |
| `spring.datasource.hikari.connection-timeout` | 30000 ms | 1000 – 60000 ms | `connection_pool_exhaustion` |
| `spring.task.execution.pool.max-size` | 2 | 2 – 32 | `thread_pool_saturation` |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | 1 | 1 – 100 | `inefficient_query` |
| `perf-lab.cache.enabled` | false | true / false | `cache_miss` |

---

## Metrics reference

All latency timers are in **seconds** in Micrometer's raw output. Crucible's
collector converts them to milliseconds before any model sees them.

| Metric | Type | Notes |
|---|---|---|
| `hikaricp.connections.max` | Gauge | Pool size currently in force |
| `hikaricp.connections.active` | Gauge | Connections currently checked out |
| `hikaricp.connections.pending` | Gauge | Threads waiting for a connection |
| `hikaricp.connections.acquire` | Timer | Time spent waiting for a connection |
| `http.server.requests` | Timer | Per uri/status/method/outcome |
| `jvm.memory.used` | Gauge | Per memory pool |
| `jvm.gc.pause` | Timer | GC pause duration |
| `executor.pool.size` | Gauge | Async executor thread pool size |
| `executor.active` | Gauge | Active async executor threads |
| `executor.queued` | Gauge | Queued async tasks |
| `cache.gets` | Counter | Cache hit/miss per cache name |

**Aggregation note:** `http.server.requests` splits by label. A bare series name
returns many series; aggregate with PromQL `histogram_quantile` or sum across labels
before comparing to the SLA.

---

## Tracing

Jaeger is optional (lab profile only). Traces are emitted via OTLP gRPC to
`localhost:4317`. Sampling rate: 100% in lab, 10% in any load scenario that
generates >200 rps (to avoid exhausting Jaeger's ingest on the free-tier box).

`available_evidence.trace_sampling_rate` in the Crucible snapshot records the
effective rate so the agent knows whether absence of slow spans is genuine or
a sampling artefact.

---

## Ports (lab profile)

| Service | Port | Protocol |
|---|---|---|
| Spring Boot app | 8080 | HTTP |
| Actuator | 8080 | HTTP (same as app) |
| Prometheus | 9090 | HTTP |
| Jaeger UI | 16686 | HTTP |
| Jaeger OTLP gRPC | 4317 | gRPC |
| Postgres | 5432 | TCP (localhost only) |
| Redis | 6379 | TCP (localhost only) |
| httpbin | 8081 | HTTP |
