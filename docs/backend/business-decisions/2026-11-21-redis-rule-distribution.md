# Distribute rule creations through Redis

- **Date** — 2026-11-21
- **Related PR** — [Redis rule distribution](https://github.com/miksoleq/goldman-hackathon/pulls?q=is%3Apr+head%3Afeature%2Fredis-rule-distribution)
- **Context** — Each Engine replica matches incoming events against its own in-memory rule index and needs every new rule, including rules created while it was offline.
- **Decision** — Control saves a publication task alongside each rule in PostgreSQL. The publisher writes the envelope to a Redis hash and broadcasts it via Pub/Sub in one script. Engine subscribes and reads the hash after connecting.
- **Rationale** — Connected replicas can immediately index an individual rule. The hash lets a returning replica recover definitions it missed.
- **Impact** — Teams can keep creating rules during a Redis outage. Those rules take effect after publication resumes. Recovery after a replica restart depends on the retained Redis hash. Kafka remains available for event ingestion.
