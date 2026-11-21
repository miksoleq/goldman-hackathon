# Control Panel rule management

- **Date:** 2026-11-21
- **Related PR:** To be added
- **Context:** Operators need a dedicated Control interface to review event activity and create alert rules without authoring YAML or JSON by hand.
- **Decision:** Build the Control Panel as an independent React application in `control-panel/`. Provide an overview, rule and activity views, and a guided rule builder that supports nested conditions, every v1 condition operator, recursive structural JSON schemas, alert routing, deduplication, runbook selection, and rule activation.
- **Rationale:** A separate application keeps the Control experience independent from the read-only Event Monitor. Guided controls serialize to the existing Control API contract, while the backend remains authoritative for validation and persistence.
- **Impact:** The panel can create and inspect rules and monitor current messages, alerts, and runbooks. Team and username recipients require exact names because Control does not currently expose recipient catalog endpoints. Rule edits and deletion are unavailable because the API does not provide those operations.
