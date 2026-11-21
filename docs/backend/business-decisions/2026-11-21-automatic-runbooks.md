# Automatic runbook selection

- **Date** 2026-11-21
- **Related branch** `feat/automatic-runbook-selection`, based on the shared compiler from `feat/shared-rule-validation`.
- **Context** Multiple enabled runbooks can match one event. Responders need one instruction on the alert.
- **Decision** The product owner chose the first matching name in alphabetical order. Comparison ignores case, then uses the original name to resolve case-only ties consistently across replicas.
- **Rationale** The winner can be predicted from existing unique runbook names.
- **Impact** Explicit attachments take precedence. Automatic instructions reflect the current catalog, propagate on the polling interval, and are captured on alert creation. Later edits, disables, and count increments preserve the saved instruction.
