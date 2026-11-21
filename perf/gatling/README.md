# Engine and notification load test

`run-load-test.sh` starts an isolated Compose project with its own PostgreSQL and Redis volumes, three Engine replicas behind HAProxy, Control, and a unique matching rule. It drives the normal HTTP ingest endpoint with Gatling. Every event has a unique deduplication key, matches the test rule, creates an alert, and creates a UI notification for the seeded `demo.member` recipient. It verifies the accepted HTTP requests, alert count, and UI notification count. The database query reports p50/p95/p99 latency from the Engine's event-acceptance timestamp (`occurredAt`) to the UI notification row commit; the UI push is dispatched immediately after that transaction commits.

The default `PERF_CHANNELS=ui` profile measures UI push at 1,000 RPS. To exercise all configured recipient channels, run `PERF_CHANNELS=ui,email,sms` (the script seeds valid synthetic contacts and enables these preferences for the demo recipient). In that profile, the report includes the per-channel `sent`, `pending`, `failed`, or `throttled` counts. The worker sends outbound notifications asynchronously; the UI latency query is not a substitute for actual SMTP/SMS receipt time.

## Run the 1,000 RPS profile

Prerequisites: Docker Compose v2.24+ (the override uses `!override`), network access for Maven dependencies, `curl`, `jq`, and Python 3 (to allocate free loopback ports). The test uses a unique Compose project and named volumes, does not stop or remove other projects, and removes only its own project and volumes on exit. Gatling HTML reports remain in `target/gatling/`.

From the repository root:

```bash
perf/gatling/run-load-test.sh
```

Defaults are 1,000 events/second, a 15-second ramp, 60 seconds at the target rate, and three Engine replicas. The profile asserts zero failed HTTP requests, HTTP 202 for each request, p95 HTTP response time below 1 second, every accepted event becoming an alert and UI notification within 600 seconds, and UI end-to-end p95 below 5 seconds. Set `DRAIN_TIMEOUT_SECONDS` or `MAX_UI_P95_MS` to change those last two limits. A run that accepts events but misses the recipient latency target exits nonzero after printing its channel status counts.

Override the offered rate, duration, ramp, replica count, or retain the isolated stack for inspection with environment variables:

```bash
RPS=1000 DURATION_SECONDS=60 RAMP_SECONDS=15 ENGINE_REPLICAS=3 PERF_CHANNELS=ui,email,sms \
MAX_UI_P95_MS=5000 KEEP_PERF_STACK=1 \
  perf/gatling/run-load-test.sh
```

The engine-only smoke simulation is still available against a separately running Engine:

```bash
docker run --rm -v "$PWD/perf/gatling:/work" -w /work maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp gatling:test \
  '-Dgatling.simulationClass=com.goldman.alerting.perf.HttpContractSimulation' \
  '-DbaseUrl=http://host.docker.internal:8081'
```

It checks health, JSON and text acceptance (`202`), malformed JSON (`400 invalid_json`) and empty input (`400 invalid_request`).

## Interpreting results

Gatling's response latency is the synchronous Engine ingest path: HTTP parsing, rule match, alert construction, Redis deduplication, and stream publication. The SQL latency is asynchronous end-to-end time through Control's alert consumer and UI notification persistence/push dispatch. The exact request rate and total accepted events are shown in the Gatling HTML report; the output from the orchestration script reports alert and UI delivery counts. Keep the load generator and application stack on separate hosts for production capacity claims. A same-host Compose run is a reproducible local capacity test, not a production guarantee.
