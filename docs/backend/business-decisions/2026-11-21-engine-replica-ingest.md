# Engine replica ingest decision

- **Date:** 2026-11-21
- **Related PR:** [#19](https://github.com/miksoleq/goldman-hackathon/pull/19)
- **Context:** The ingest Engine must scale horizontally without each replica keeping independent alert-deduplication or broker-retry state.
- **Decision:** Keep rule configuration as a per-replica immutable snapshot distributed by Control, and keep deduplication and broker-replay checkpoints in shared Redis. Atomically publish processed events/alerts with Redis Stream acknowledgements or Kafka offset checkpoints. Route HTTP traffic through HAProxy to healthy Engine replicas.
- **Rationale:** Shared Redis state prevents duplicate alert buckets and replay handling from diverging between replicas. Atomic publication and acknowledgement/checkpoint updates make retries safe across replica restarts. The load balancer gives HTTP producers one stable endpoint while replicas can be scaled independently.
- **Impact:** Compose starts three Engine replicas by default. Redis remains a shared dependency for deduplication and broker idempotency; Kafka throughput still depends on having enough topic partitions for consumer concurrency. The Lua publication path is more complex, and Kafka replay checkpoints expire after the configured TTL.
