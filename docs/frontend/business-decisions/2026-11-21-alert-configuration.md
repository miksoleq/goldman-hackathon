# Alert configuration

- **Date:** 2026-11-21
- **Related PR:** Alert configuration redesign
- **Context:** Rule authors need clear recipient selection, alert content, and advanced settings.
- **Decision:** Group alert settings and provide searchable recipient chips, template insertion, a preview, and deduplication field chips. Allow exact-name entry when recipient directories are unavailable.
- **Rationale:** Guided controls make alert creation easier while preserving the current Control rule contract.
- **Impact:** Recipient catalog endpoints are not yet present. Engine currently saves but does not enforce deduplication, priority ordering, stop processing, or runbook assignment to generated alerts; the interface explains these limitations.
