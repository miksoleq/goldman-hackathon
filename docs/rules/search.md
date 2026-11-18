# Search and Indexing

- Start with the chosen data store's search capabilities. Use a separate index when the challenge needs capabilities such as relevance ranking or full-text search that the current store cannot provide readily.
- Define which fields can be searched, filtered, and sorted. Bound results and give pagination a deterministic tie-breaker. Build queries through typed APIs or bound parameters.
- If a separate index is used, keep the source of truth explicit. Define how documents are updated and rebuilt. Account for indexing delay in user-facing behavior and consult authoritative data for write decisions.
- When mappings change, state whether existing demo data needs a rebuild. Test a representative query and any asynchronous indexing behavior against the actual engine when it matters to the demo.
