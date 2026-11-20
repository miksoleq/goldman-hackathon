# Business decision note

- **Date:** 2026-11-20
- **Related PR:** https://github.com/miksoleq/goldman-hackathon/pull/1
- **Context:** The first engine slice needs to accept JSON from HTTP, Redis Streams, and Kafka before any schema or business-processing rules have been agreed.
- **Decision:** Preserve each input as an unrestricted JSON value in `payload`, add a small transport envelope, and publish all accepted messages to the `events:ingest` Redis Stream. Keep Kafka ingestion optional.
- **Rationale:** Producers can send different JSON shapes while downstream processing is still being designed. One internal stream gives later workers a common entry point, and the optional Kafka adapter follows the architecture proposal without requiring Kafka for local startup.
- **Impact:** The engine can acknowledge HTTP messages after enqueueing and normalize Redis/Kafka input into the same envelope. Messages are limited to 1 MiB by default; business validation, dead-letter handling, and pending-message recovery remain future work.
