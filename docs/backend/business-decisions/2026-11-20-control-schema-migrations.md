# Business decision note

- **Date:** 2026-11-20
- **Related PR:** Pending creation
- **Context:** The initial PostgreSQL schema needs a repeatable setup, and one backend module must own schema-version changes.
- **Decision:** Run PostgreSQL 17 through Docker Compose with a persistent named volume. Put versioned SQL migrations in the `control` module and let Flyway validate and apply pending migrations when `control` starts. Expose the current, applied, and pending schema versions through a localhost-only endpoint.
- **Rationale:** A migration history table makes schema changes observable and prevents already applied migrations from being run again on every startup. Keeping migration ownership in `control` gives future configuration management one place to coordinate database changes.
- **Impact:** Local startup creates and versions the database through Compose and `control`. This first slice does not implement configuration CRUD or update the `config_version` counter.
