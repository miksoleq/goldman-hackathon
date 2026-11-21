# Runbook instructions on alerts

- **Date:** 2026-11-21
- **Related PR:** [Attach runbook instructions to alerts](https://github.com/miksoleq/goldman-hackathon/compare/main...feature/alert-runbooks?expand=1)
- **Context:** Responders need the instructions that applied when an event triggered an alert.
- **Decision:** A rule author can select an active runbook by name or create one with the rule. Engine fills text and Markdown templates with event values and copies static HTTP(S) links. Control stores the resulting instructions with the alert.
- **Rationale:** Authors can reuse instructions across rules, and responders can read the event-specific instructions even after the runbook changes.
- **Impact:** The alert detail API returns the stored instructions when available. Existing alerts and alerts from rules without an attached runbook may have none.
