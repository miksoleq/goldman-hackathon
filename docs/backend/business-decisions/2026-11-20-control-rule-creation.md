# Rule creation in control

- **Date:** 2026-11-20
- **Related PR:** [Create alert rules in control](https://github.com/miksoleq/goldman-hackathon/pulls?q=head%3Afeature%2Fcontrol-rule-creation)
- **Context:** Teams need to register alert rules even when the processing engine is offline.
- **Decision:** Accept version 1 rule documents, validate team and runbook references, and store both the editable source and normalized definition. Commit the rule, routing, audit, history and publication request together.
- **Rationale:** A successful API response confirms durable storage. Kafka delivery can resume independently after an outage.
- **Impact:** Duplicate names return 409. Consumers can identify repeat deliveries by rule ID and version. Creating a rule does not itself evaluate events.
