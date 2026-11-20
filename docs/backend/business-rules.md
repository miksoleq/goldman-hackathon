# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when the expected behavior or contract changes. Use Git history to trace earlier decisions.

Document a change in the same commit as the implementation and the focused test that verifies it. For a bug fix that restores an existing rule, capture the failing case in a regression test.

## Current rules

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
