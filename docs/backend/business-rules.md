# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when the expected behavior or contract changes. Use Git history to trace earlier decisions.

Document a change in the same commit as the implementation and the focused test that verifies it. For a bug fix that restores an existing rule, capture the failing case in a regression test.

## Current rules

### Control owns database schema versions

- **Expected behavior** — On startup, `control` validates and applies pending PostgreSQL migrations from `backend/control/src/main/resources/db/migration`. `GET /internal/migrations` reports the current schema version and applied and pending migrations; with the initial schema applied, the response reports version `1` and an empty `pending` list.
- **Exceptions and boundaries** — Flyway records applied versions in `flyway_schema_history`; an already applied migration is not run again. The endpoint is bound to localhost for this local skeleton. This module manages database schema versions only; configuration CRUD and configuration-version updates are not implemented yet.
- **Reason** — `control` is the designated owner for database migrations and provides one place to see the schema version before configuration-management features are added.
- **Verification** — `docker compose build control`; `docker compose up -d --force-recreate control`; `Invoke-RestMethod http://localhost:8082/internal/migrations` returned `currentVersion: "1"`, applied migration `V1` in state `SUCCESS`, and no pending migrations. PostgreSQL confirmed one successful `flyway_schema_history` row for version `1`.

### Ingest accepts schema-less JSON

- **Expected behavior** — `POST /ingest` accepts any JSON value and returns `202` with an `eventId` after publishing an event envelope to `events:ingest`. HTTP envelopes have `source: null` and `adapter: "http"`. Business source fields are optional and remain in the original payload. Redis Stream entries on `ingest:external` use a `payload` field containing JSON text and an optional `source` field. Kafka values contain JSON text. Both adapters publish to the same internal stream.
- **Exceptions and boundaries** — HTTP requests must use `application/json`; malformed or empty bodies return `400`, and messages over the configured limit return `413`. The default limit is 1 MiB across the adapters. Invalid Redis and Kafka JSON records are dropped. The envelope records `eventId`, `source`, `adapter`, `receivedAt`, `contentType`, and the untouched JSON value in `payload`.
- **Reason** — The first engine slice only needs to accept events from the three specified transports; business validation and processing can be added later without constraining producer payloads.
- **Verification** — Manual smoke flow is documented in the root `README.md`; automated tests have not been added in this initial skeleton.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** — state when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** — describe relevant edge cases and their exact outcomes.
- **Reason** — record the brief requirement or team decision behind the rule.
- **Verification** — link to the test or name the test case that checks the rule.
