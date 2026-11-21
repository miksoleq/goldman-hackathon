# Business decision note

- **Date:** 2026-11-21
- **Related PR:** [#8](https://github.com/miksoleq/goldman-hackathon/pull/8)
- **Context:** The hackathon demo needs an operator view of messages, rules, runbooks, and alerts without placing the UI inside the backend service.
- **Decision:** Build a separate React dashboard served by its own Compose service. Keep all user-facing copy in English, use a restrained blue and slate palette, and omit a company name and logo.
- **Rationale:** A dedicated frontend keeps the UI independently buildable while the selected views make the current Engine and Control state inspectable in one place.
- **Impact:** The dashboard is read-only and consumes Control's monitor endpoints. It works with the existing Compose services and presents real API data, empty states, and recoverable feed errors.
