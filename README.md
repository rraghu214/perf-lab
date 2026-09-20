# PerfLab

A Spring Boot service that is **deliberately easy to make slow**, in eight
specific and different ways.

It exists to be the target of [Crucible](https://github.com/rraghu214/crucible),
an autonomous performance engineer that runs load experiments, diagnoses why a
service missed its latency objective, proposes a bounded configuration change,
re-measures, and keeps or reverts the change on the evidence. You cannot evaluate
an agent like that without a service whose bottlenecks you already know the
answers to — that is what this is.

It is also useful on its own as a teaching fixture: every endpoint is a textbook
performance failure you can reproduce in one command.

## One endpoint per cause family

| Endpoint | Cause family | What is actually wrong |
|---|---|---|
| `/api/fast` | — | baseline, no dependency |
| `/api/db` | `connection_pool_exhaustion` | holds a pooled connection across a simulated 50 ms of DB latency |
| `/api/async` | `thread_pool_saturation` | more queued work than the executor can drain |
| `/api/churn` | `gc_pressure` | allocates and retains, repeatedly |
| `/api/orders` | `inefficient_query` | a genuine N+1, with batch fetching off |
| `/api/catalog` | `cache_miss` | reads through to the database when the cache is disabled |
| `/api/downstream` | `downstream_latency` | a slow third party (httpbin `/delay`) |
| `/api/ledger` | `lock_contention` | a synchronised section under concurrent load |
| `/api/payload` | `payload_serialization` | serialises thousands of rows per request |

The families are not decoration. An agent diagnosing this service has to name
which one it is, and a fixture whose cause is ambiguous teaches nothing — so each
endpoint fails for one reason and responds to one fix.

## Running it

Zero config, no containers — H2 in memory:

```bash
./mvnw spring-boot:run
```

With the real dependency stack (Postgres, Redis, go-httpbin, Prometheus):

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=lab
```

Every port in `docker-compose.yml` binds to `127.0.0.1` only. On a cloud box with
a public IP, a bare `5432:5432` binds every interface, and the only thing between
an unauthenticated Redis and the internet is a correctly configured firewall.

## `/api/version` and why the commit matters

```json
{"app":"perf-lab","version":"0.2.0","poolSize":20,"commit":"9f2c1ab..."}
```

`poolSize` is read from **resolved** configuration, so it reports what is
actually in force rather than what a properties file says. `commit` comes from
`META-INF/build-info.properties`, populated at package time:

```bash
./mvnw -B -DskipTests -Dperflab.commit=$(git rev-parse HEAD) package
```

Build without that flag and `commit` is reported as `null`, never a placeholder.
That is deliberate. A tool measuring this service needs to know it is running the
build that was just deployed, and a plausible-looking wrong answer is worse than
an absent one — an absent one stops the measurement, a wrong one gets believed.

This is not hypothetical. During an earlier benchmark a full set of measurements
was taken against a JVM that had never been restarted; it was caught only because
a connection pool of 20 cannot cap active connections at 2, and every other
figure looked entirely ordinary.

## Metrics

Micrometer, over Actuator and Prometheus:

- `/actuator/metrics/{name}` — one metric, Actuator's shape
- `/actuator/prometheus` — the scrape endpoint

Two things are worth knowing if you are reading these programmatically.

**Some meters do not exist until they are used.**
`hikaricp_connections_acquire_seconds_*` and `hikaricp_connections_active` are
absent on an idle target and appear only after the pool has served requests. A
reader that queries a cold instance will correctly, and confusingly, find
nothing.

**Some meters split across labels.** `http_server_requests_seconds_count` is per
uri/status/method/outcome and `jvm_memory_used_bytes` is per memory pool, so a
bare series name returns many series rather than one. They need aggregating — and
`count` and `sum` add up while `max` does not: the slowest request in a service
is the largest per-URI maximum, not the total of them.

## Configuration the agent is allowed to change

Crucible may tune a small allowlist — pool sizes, timeouts, thread pools, batch
fetch sizes, the cache toggle — declared in Crucible's own target profile, with
bounds, and never here. Listing a knob in `application.properties` grants
nothing. That separation is the point: the service describes itself, and
something else decides what may be touched.

## Licence

MIT.
