# Business Rules

### Recipient directory

- **Expected behavior** — Control returns team names at `GET /api/teams` and usernames at `GET /api/users`, ordered by name. These names match the identifiers accepted when creating a rule.
- **Exceptions and boundaries** — Empty directories return empty arrays. Directory responses exclude contact information.
- **Verification** — `RecipientControllerTest` covers controller delegation; `RecipientControllerIT` checks persisted records through HTTP.

Use this file for current business behavior and agreed contracts between components. Update an entry when expected behavior or the contract changes.

Document a change in the same commit as the implementation and focused tests. For a bug fix, add a regression case when practical.

## Current rules

### Rule matching uses immutable indexed snapshots

- **Expected behavior** - Engine parses the `match` subtree from rule-definition v1 into an immutable condition tree. It evaluates `all`, `any`, and `not`, plus leaf operators `exists`, `eq`, `ne`, `in`, `nin`, numeric comparisons, `contains`, `startsWith`, `endsWith`, and `regex`. `CompiledPath` supports `payload` and `meta` roots, dotted keys, quoted keys, array indexes, and `[*]`. Each rule uses one best equality bucket for `all`, one bucket per indexable `any` branch, or the always-check list; every candidate still runs its full predicate.
- **Exceptions and boundaries** - Missing paths and JSON `null` produce no resolved values; leaf operators return false when no values resolve, while `exists:false` returns true. Wildcard paths use existential matching. Numeric comparisons accept numbers and strings parseable as `BigDecimal`; equality preserves JSON types and compares numbers numerically. Regex uses RE2/J `find` semantics. A new rule is compiled into a new immutable snapshot through `RuleIndexManager.accept`; the current and old snapshots remain safe for concurrent readers. Engine consumes enabled rule events from `rules.created.v1`; invalid messages are logged and ignored. The Control publisher writes the outbox as part of the creation transaction.
- **Reason** - Schema-less payloads need flexible conditions, while event evaluation should inspect only likely candidates and remain safe during rule updates.
- **Verification** - `RuleIndexTest` covers indexed `all`, indexable and fallback `any`, `in` bucket deduplication, wildcard paths, missing and wrong-type values, numeric and text operators, RE2/J, immutable hot updates, and 5,000 rules with one evaluated candidate. `RuleIndexIntegrationIT` covers parser-to-index matching and concurrent readers. `RedisRuleCreatedAdapterIT` covers event consumption, bucket creation, and snapshot recovery against Redis. Run all backend checks with `mvn -B -pl control,engine -am clean verify` inside the Java 21 Maven Docker image.

### Control owns database persistence

- **Expected behavior** - Control is the only service that reads or writes application data in PostgreSQL. It uses jOOQ for queries and writes, and Flyway for versioned schema migrations. Creating a rule stores the rule, user and team links, version history, audit entry, configuration revision, and Redis outbox event in one database transaction.
- **Exceptions and boundaries** - Invalid rule documents or unknown team/runbook references reject the request; duplicate names return `409`. Redis publishing is controlled by `RULES_REDIS_ENABLED`. If Redis is unavailable after the database transaction commits, the outbox retains the event for retry. Engine has no database connection; it maintains rule buckets only in memory.
- **Reason** - Control owns durable configuration and schema versions. Engine consumes the resulting rule definition without coupling matching to PostgreSQL or jOOQ.
- **Verification** - Control rule creation tests exercise validation, persistence, and outbox behavior. The engine Redis integration test verifies the consumer contract; the full Control-to-Engine smoke flow is documented in the root `README.md`.

### Create a rule in Control

A valid editor document creates the rule, user and team links, version 1, audit entry, configuration revision, and Redis outbox entry in one PostgreSQL transaction. Unknown references or invalid definitions reject the whole request. The original YAML is retained for editing. Rule creation succeeds independently of the engine. The root README contains the request example, response contract, and Redis setup.

### Rule-created Redis contract

- **Expected behavior** - Control publishes each created rule to `rules.created.v1`, using Redis Pub/Sub with the latest envelope per rule id in the companion hash. The JSON value contains `type`, `ruleId`, `version`, `configVersion`, `name`, `enabled`, `priority`, `teamIds`, `userIds`, `runbookId`, and `definition`. `definition` is the normalized v1 JSON document from the rule-definition schema. Engine uses `ruleId`, `enabled`, and `definition` to build matching buckets. Engine includes the configured recipients in each matched alert on `alerts.live`.
- **Exceptions and boundaries** - Disabled rules are not added to the active index. Duplicates and older versions are ignored. Invalid events do not alter the current snapshot. Control's outbox may deliver a record more than once, so consumers must be idempotent by rule id and version.
- **Reason** - The v1 definition schema excludes database identity and enabled state; those fields are supplied by Control in the event envelope.
- **Verification** - `RedisRuleCreatedAdapterTest` covers accepted, disabled, and malformed events. `RedisRuleCreatedAdapterIT` verifies the real Redis listener against Redis.

### Ingest accepts schema-less JSON

- **Expected behavior** - `POST /ingest` accepts any JSON value and returns `202` with an `eventId` after publishing an event envelope to `events:ingest`. HTTP envelopes have `source: null` and `adapter: "http"`. Business source fields are optional and remain in the original payload. Redis Stream entries on `ingest:external` use a `payload` field containing JSON text and an optional `source` field. Kafka values contain JSON text. Both adapters publish to the same internal stream.
- **Exceptions and boundaries** - HTTP requests must use `application/json`; malformed or empty bodies return `400`, and messages over the configured limit return `413`. The default limit is 1 MiB across the adapters. Invalid Redis and Kafka JSON records are dropped. The envelope records `eventId`, `source`, `adapter`, `receivedAt`, `contentType`, and the untouched JSON value in `payload`.
- **Reason** - The first engine slice accepts messages from the three specified transports without constraining producer payloads.
- **Verification** - Manual smoke flow is documented in the root `README.md`; automated ingest tests have not been added in this initial skeleton.

### Durable alert reception

Engine appends one JSON field `alert` per matched rule to stream `alerts.live`. Control reads the backlog, persists one inbox row per alert UUID, and acknowledges after commit. A duplicate delivery produces one stored row and one application event on successful synchronous processing. `LiveAlertSubscriptionIT` exercises backlog delivery and reclaim of a pending duplicate with PostgreSQL and Redis.

### Event Monitor read API

- **Expected behavior** — Control exposes GET `/api/monitor/messages`, `/rules`, `/runbooks`, and `/alerts`. Messages come from the configured ingest Redis stream; rules and runbooks come from PostgreSQL; alerts come from the persisted `live_alert_inbox`. Each endpoint returns at most the newest 100 records.
- **Exceptions and boundaries** — These endpoints are read-only. Missing streams and empty tables return empty arrays. Control remains the only service that queries PostgreSQL.
- **Reason** — The demo dashboard needs to inspect current Engine and Control state without bypassing Control's data ownership.
- **Verification** — `MonitorControllerIT` covers the four endpoints with Redis and PostgreSQL.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** - State when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** - Describe relevant edge cases and their exact outcomes.
- **Reason** - Record the brief requirement or team decision behind the rule.
- **Verification** - Link to the test or name the test case that checks it.

### Attached runbook instructions

Control accepts an existing active runbook name or an inline object with name, format and content on rule creation. Inline creation and outbox insertion share the rule transaction. Engine renders text and Markdown using the matched event, and copies static HTTP(S) links. The alert inbox preserves the rendered snapshot. Unassigned rules (including auto in this release) produce alerts without instructions. Flyway V6 republishes previously attached instructions with a higher rule version.
