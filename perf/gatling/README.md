# Engine ingest load test

This Gatling scenario exercises the complete synchronous HTTP ingest path: JSON parsing, the in-memory rule index, alert construction, Redis deduplication and stream publication. Gatling VUs share a long-lived HTTP connection pool to model a service producer. Configure a matching rule to include alert creation and deduplication in the measurement.

`HttpContractSimulation` is a short HTTP smoke test for actuator health, accepted JSON and text payloads, malformed JSON and empty bodies.

Start the engine with a dedicated Redis stream and a seeded `rules.created.v1:snapshot` rule. Keep Gatling on the same machine or network as the engine, and avoid unrelated load during a run. Reports are written to `target/gatling/`.

Run from this directory with Maven and a JDK supported by Gatling:

```powershell
mvn -B -ntp gatling:test `
  '-Dgatling.simulationClass=com.goldman.alerting.perf.EngineIngestSimulation' `
  '-Drps=50' '-DrampSeconds=10' '-DdurationSeconds=30' `
  '-DmaxP95Ms=1000' `
  '-DbaseUrl=http://localhost:8081'
```

Repeat at increasing rates (for example 25, 50, 100 and 200 requests/second). Each virtual user submits one event, and every response must be HTTP 202. The run also fails when p95 exceeds `maxP95Ms` (default 1000 ms). Use the HTML reports for throughput and latency percentiles; these are measurements, not fixed performance guarantees, and depend on host capacity and Redis configuration.

Run the HTTP contract smoke test against a running engine with Redis configured:

```powershell
mvn -B -ntp gatling:test `
  '-Dgatling.simulationClass=com.goldman.alerting.perf.HttpContractSimulation' `
  '-DbaseUrl=http://localhost:8081'
```

It checks health, JSON and text acceptance (`202`), malformed JSON (`400 invalid_json`) and empty input (`400 invalid_request`).
