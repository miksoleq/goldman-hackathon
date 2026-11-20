# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when expected behavior or the contract changes.

Document a change in the same commit as the implementation and focused tests. For a bug fix, add a regression case when practical.

## Current rules

### Rule matching uses immutable indexed snapshots

- **Expected behavior** - Engine parses the `match` subtree from rule-definition v1 into an immutable condition tree. It evaluates `all`, `any`, and `not`, plus leaf operators `exists`, `eq`, `ne`, `in`, `nin`, numeric comparisons, `contains`, `startsWith`, `endsWith`, and `regex`. `CompiledPath` supports `payload` and `meta` roots, dotted keys, quoted keys, array indexes, and `[*]`. Each rule uses one best equality bucket for `all`, one bucket per indexable `any` branch, or the always-check list; every candidate still runs its full predicate.
- **Exceptions and boundaries** - Missing paths and JSON `null` produce no resolved values; leaf operators return false when no values resolve, while `exists:false` returns true. Wildcard paths use existential matching. Numeric comparisons accept numbers and strings parseable as `BigDecimal`; equality preserves JSON types and compares numbers numerically. Regex uses RE2/J `find` semantics. A new rule is compiled into a new immutable snapshot through `RuleIndexManager.accept`; the current and old snapshots remain safe for concurrent readers. Engine consumes enabled rule events from `rules.created.v1`; invalid messages are logged and ignored. The Control publisher writes the outbox as part of the creation transaction.
- **Reason** - Schema-less payloads need flexible conditions, while event evaluation should inspect only likely candidates and remain safe during rule updates.
- **Verification** - `RuleIndexTest` covers indexed `all`, indexable and fallback `any`, `in` bucket deduplication, wildcard paths, missing and wrong-type values, numeric and text operators, RE2/J, immutable hot updates, and 5,000 rules with one evaluated candidate. `RuleIndexIntegrationIT` covers parser-to-index matching and concurrent readers. `KafkaRuleCreatedAdapterIT` covers event consumption, bucket creation, and replacement through an embedded Kafka broker. Run all backend checks with `mvn -B -pl control,engine -am clean verify` inside the Java 21 Maven Docker image.

### Control owns database persistence

- **Expected behavior** - Control is the only service that reads or writes application data in PostgreSQL. It uses jOOQ for queries and writes, and Flyway for versioned schema migrations. Creating a rule stores the rule, team links, version history, audit entry, configuration revision, and Kafka outbox event in one database transaction.
- **Exceptions and boundaries** - Invalid rule documents or unknown team/runbook references reject the request; duplicate names return `409`. Kafka publishing is controlled by `RULES_KAFKA_ENABLED`. If Kafka is unavailable after the database transaction commits, the outbox retains the event for retry. Engine has no database connection; it maintains rule buckets only in memory.
- **Reason** - Control owns durable configuration and schema versions. Engine consumes the resulting rule definition without coupling matching to PostgreSQL or jOOQ.
- **Verification** - Control rule creation tests exercise validation, persistence, and outbox behavior. The engine Kafka integration test verifies the consumer contract; the full Control-to-Engine smoke flow is documented in the root `README.md`.

### Create a rule in Control

A valid editor document creates the rule, team links, version 1, audit entry, configuration revision, and Kafka outbox entry in one PostgreSQL transaction. Unknown references or invalid definitions reject the whole request. The original YAML is retained for editing. Rule creation succeeds independently of the engine. The root README contains the request example, response contract, and Kafka setup.

### Rule-created Kafka contract

- **Expected behavior** - Control publishes each created rule to `rules.created.v1`, using the rule id as the Kafka key. The JSON value contains `type`, `ruleId`, `version`, `configVersion`, `name`, `enabled`, `priority`, `teamIds`, `runbookId`, and `definition`. `definition` is the normalized v1 JSON document from the rule-definition schema. Engine uses `ruleId`, `enabled`, and `definition` to build matching buckets; the remaining fields are metadata for future rule ordering and routing.
- **Exceptions and boundaries** - Disabled rules are not added to the active index. Repeated events for an existing `ruleId` replace that rule in a new snapshot. Invalid events do not alter the current snapshot. Control's outbox may deliver a record more than once, so consumers must be idempotent by rule id and version.
- **Reason** - The v1 definition schema excludes database identity and enabled state; those fields are supplied by Control in the event envelope.
- **Verification** - `KafkaRuleCreatedAdapterTest` covers accepted, disabled, and malformed events. `KafkaRuleCreatedAdapterIT` verifies the real Kafka listener with an embedded broker.

### Ingest accepts schema-less JSON

- **Expected behavior** - `POST /ingest` accepts any JSON value and returns `202` with an `eventId` after publishing an event envelope to `events:ingest`. HTTP envelopes have `source: null` and `adapter: "http"`. Business source fields are optional and remain in the original payload. Redis Stream entries on `ingest:external` use a `payload` field containing JSON text and an optional `source` field. Kafka values contain JSON text. Both adapters publish to the same internal stream.
- **Exceptions and boundaries** - HTTP requests must use `application/json`; malformed or empty bodies return `400`, and messages over the configured limit return `413`. The default limit is 1 MiB across the adapters. Invalid Redis and Kafka JSON records are dropped. The envelope records `eventId`, `source`, `adapter`, `receivedAt`, `contentType`, and the untouched JSON value in `payload`.
- **Reason** - The first engine slice accepts messages from the three specified transports without constraining producer payloads.
- **Verification** - Manual smoke flow is documented in the root `README.md`; automated ingest tests have not been added in this initial skeleton.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** - State when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** - Describe relevant edge cases and their exact outcomes.
- **Reason** - Record the brief requirement or team decision behind the rule.
- **Verification** - Link to the test or name the test case that checks it.
