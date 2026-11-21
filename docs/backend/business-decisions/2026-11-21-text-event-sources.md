# Text events and source identity

- **Date** 2026-11-21
- **Related branch** `feat/ingest-text-metadata`
- **Context** The OOM rule matches raw log lines and renders the producer name.
- **Decision** Each plain-text HTTP body or complete log-file line represents one event. HTTP producers can name their source in the URL. Existing clients using the unqualified endpoint receive source `http`.
- **Rationale** A producer can distinguish transport metadata from fields supplied in a JSON payload and use the existing rule and deduplication behavior for log lines.
- **Impact** Redis events identify their adapter as `redis-stream`. File input is opt-in and uses one reader per configured file. HTTP JSON response and error formats are preserved.
