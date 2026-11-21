# Gatling results — 2026-11-21

The test ran the current engine branch as one local JVM, Gatling on the same Windows host, and Redis 7.4 in Docker with AOF enabled (`appendfsync everysec`). A synthetic matching rule exercised rule matching, alert construction, Redis deduplication and stream publication. Gatling used a shared HTTP connection pool, a 3-second ramp, then a 15-second constant arrival rate. Each virtual user sent one request; successful requests had to return HTTP 202.

| Offered rate | Requests | HTTP 202 | Failed | p95 | p99 | Result |
|---:|---:|---:|---:|---:|---:|---|
| 500 RPS | 8,251 | 8,251 | 0 | 12 ms | 40 ms | Pass |
| 1,000 RPS | 16,501 | 16,501 | 0 | 19 ms | 112 ms | Pass |
| 1,500 RPS | 24,751 | 24,751 | 0 | 20 ms | 42 ms | Pass |
| 1,750 RPS | 28,876 | 28,791 | 85 | 12 ms | 38 ms | 99.7% accepted |
| 2,000 RPS | 33,001 | 32,591 | 410 | 21 ms | 161 ms | 98.8% accepted |
| 2,500 RPS | 41,251 | 13,110 | 28,141 | 15.7 s | 20.3 s | Saturated |

At 2,500 RPS, failures included HTTP 500 responses and connection refusals. The engine log recorded `RedisCommandTimeoutException: Command timed out after 2 second(s)` while publishing. Redis slowlog showed an `EVALSHA` call taking about 47 ms at its peak; it did not show a two-second server-side script. This points to queuing or scheduling pressure somewhere in the shared local environment, with the first observed application failure at the Redis publisher. It does not yet isolate Redis server CPU from the engine, Lettuce client, operating system, or Gatling generator.

The 2,500 RPS run had 4,821 HTTP 500 responses and 23,320 connection refusals. The post-run Redis snapshot reported no delayed AOF fsync. These observations do not prove which local process or queue caused the two-second client timeout.

The practical single-instance range on this machine was clean through 1,500 RPS. Errors started above that and latency collapsed at 2,500 RPS. These figures are local measurements, not production capacity: Gatling and the engine shared the same host, and the run did not use multiple engine replicas or a load balancer.

The separate `HttpContractSimulation` passed all five HTTP checks: health, JSON acceptance, text acceptance, malformed JSON handling, and empty-body handling.

HTML reports remain under `target/gatling/` after running the simulations. The high-rate runs use the `engineingestsimulation-20261121053124168`, `engineingestsimulation-20261121053151813`, `engineingestsimulation-20261121053218949`, `engineingestsimulation-20261121053450036`, `engineingestsimulation-20261121053517500`, and `engineingestsimulation-20261121053716274` report directories.

## Current `main` Compose test — 2026-11-21

The new `run-load-test.sh` profile built an isolated stack from the checked-out `main` source: three Engine replicas behind HAProxy, one Control instance, dedicated PostgreSQL and Redis volumes, and one matching rule routing unique events to the seeded `demo.member`. The Gatling generator shared Docker Desktop with the application and several unrelated demo/test stacks already running on the same Mac (8 CPUs, about 7.7 GiB Docker memory). This is a local integration measurement, not an isolated-host capacity claim.

The 1,000 RPS profile held the target for 60 seconds after a 15-second ramp and matched every event to the rule, with a unique deduplication key and a UI notification for each alert.

| Measurement | Result |
|---|---:|
| Offered constant rate | 1,000 requests/s |
| Gatling requests | 67,507 |
| HTTP 202 / failed | 67,507 / 0 |
| Engine HTTP p50 / p95 / p99 / max | 0 / 2 / 5 / 47 ms |
| UI notification rows committed | 67,507 |
| Engine event acceptance → Control UI notification commit p50 | 125,054 ms |
| Engine event acceptance → Control UI notification commit p95 | 240,614 ms |
| Same end-to-end p99 / max | 251,162 / 253,605 ms |

Gatling's request path accepted the full 1,000 RPS profile, but Control could not keep up with the alert stream during the load phase. It caught up after the load stopped, at which point all UI notification rows existed. The p95 above measures database notification commit; the application dispatches the push immediately after commit, but the current SSE payload has no event ID/acknowledgement, so this does not measure browser receipt or paint time. The end-to-end target therefore remains unmet if it expects near-real-time recipient delivery.

The report for this UI-only run is `target/gatling/engineingestsimulation-20261121072446014/index.html`.

A second run enabled all three channels (`PERF_CHANNELS=ui,email,sms`) at the same 1,000 RPS, for 30 seconds after a 15-second ramp. Gatling accepted all 37,507 requests (0 failures; Engine HTTP p95 3 ms, p99 9 ms, max 63 ms). Control eventually committed all 37,507 UI notification rows, but the recipient delay was worse:

| Measurement | Result |
|---|---:|
| End-to-end event acceptance → UI notification commit p50 / p95 | 336,838 / 576,391 ms |
| Same end-to-end p99 / max | 600,635 / 606,238 ms |
| UI notifications | 37,507 sent |
| Email mock | 1,920 sent; 35,587 pending |
| SMS mock | 1,920 sent; 35,587 pending |

During the asynchronous drain, a live authenticated SSE subscriber received 561 `notifications.changed` push events in an 8-second sample. The SSE contract sends a change hint without an event ID, so individual push events cannot currently be correlated to an ingested event or measured for browser paint time. Email/SMS `sent` means the local Mailpit/SMS simulators accepted the request; `pending` is still queued. The current schema does not record provider-acceptance timestamps, so exact email/SMS latency is not available. The all-channel Gatling report is `target/gatling/engineingestsimulation-20261121073243343/index.html`.

These runs pass Engine HTTP ingestion but fail the configured real-time end-to-end 1,000 RPS target (`MAX_UI_P95_MS=5000`): Control builds a large alert/notification backlog and external channel workers do not keep up. The result is direct evidence that a successful HTTP `202` is not a successful recipient delivery at this rate. The tests use the repository's simulator providers, not real SMTP/SMS networks. The automatic p95 threshold was added after collecting these measurements; rerunning either profile now exits nonzero after printing the channel counts.
