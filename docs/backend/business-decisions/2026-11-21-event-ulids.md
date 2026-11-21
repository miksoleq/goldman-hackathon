# Event identifiers

- **Date** 2026-11-21
- **Related branch** `feat/ingest-event-ulid`
- **Decision and rationale** Apply the event ID format from §4.2 to newly ingested events. Preserve historical UUIDs verbatim during delivery and replay so stored alerts continue to refer to the original event.
- **Impact** Engine returns a ULID from ingest. Redis messages and alert detail expose both event ID formats as JSON strings. Alert and rule IDs remain UUIDs. PostgreSQL stores the alert document in JSONB and needs no migration.
