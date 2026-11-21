# Business decision note

- **Date:** 2026-11-21
- **Related PR:** [#8](https://github.com/miksoleq/goldman-hackathon/pull/8)
- **Context:** The dashboard needs one read-only API boundary for recent engine messages and the configuration and alerts managed by Control.
- **Decision:** Add four GET endpoints under `/api/monitor`. Return the newest 100 records from the configured ingest stream, rules, runbooks, and Control's persisted live alert inbox.
- **Rationale:** Control owns PostgreSQL access and already persists the alert stream, so the monitor API can reuse that ownership and avoid a second history store.
- **Impact:** The frontend depends on Control, Redis, and PostgreSQL for live data. The endpoints expose no write operations; empty feeds return empty arrays.
