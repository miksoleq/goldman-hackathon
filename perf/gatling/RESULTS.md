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
