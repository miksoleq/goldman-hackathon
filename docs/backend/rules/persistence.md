# Persistence

- Implement application reads and writes with the jOOQ DSL and generated tables and fields, or with Hibernate using mapped entities, repositories, and the JPA Criteria API. Do not embed SQL strings in application code, including through JDBC, native Hibernate queries, or jOOQ plain SQL templates. Keep versioned Flyway migrations in SQL.

- Keep query and storage details inside the persistence boundary. Return application data from repositories and keep driver or generated types local to the adapter.
- Use bound query parameters and allowlists for user-selectable sort or filter fields. Give paginated queries a deterministic order with a unique tie-breaker. Batch related lookups if row-by-row queries become a bottleneck.
- Put schema changes in versioned migrations when the project uses them. Add a new migration for changes to a schema already used by a teammate or deployment. Tell your teammate about any migration or seed steps they need to run.
- Use transactions for writes that must succeed together. Enforce uniqueness needed for correctness and give retried writes a stable identifier when duplicate side effects matter.
- When using jOOQ, change the schema and regenerate the typed records through the build. Close streams or cursors at the point of use.
- Use the selected database for [integration tests](integration-tests.md) when query semantics, constraints, or migrations affect the result.
