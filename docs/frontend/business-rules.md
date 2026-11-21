# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when the expected behavior or contract changes. Use Git history to trace earlier decisions.

Document a change in the same commit as the implementation and the focused test that verifies it. For a bug fix that restores an existing rule, capture the failing case in a regression test.

## Current rules

### Event Monitor reads current system data

- **Expected behavior** — The React dashboard in the separate frontend service reads the newest 100 event envelopes from the configured Redis stream, up to 100 rules and runbooks from Control's database, and up to 100 persisted alert inbox records, newest first. The four feeds are available through GET /api/monitor/messages, GET /api/monitor/rules, GET /api/monitor/runbooks, and GET /api/monitor/alerts.
- **Exceptions and boundaries** — Empty feeds return an empty array. A failed feed shows a retry action while other feeds remain usable. The alert detail view shows rendered instructions and action results. Automatic steps show their mode, status and results on refresh. Explicit manual steps retain their execution controls. Disabled steps show their reason.
- **Reason** — The dashboard exposes actual Engine and Control data and reads the durable alert inbox owned by Control.
- **Verification** — MonitorControllerIT verifies all four read endpoints using Redis and PostgreSQL. React API client tests cover feed routing and malformed or failed responses. RunbookActions.test.jsx exercises automatic results without a client execution request, manual confirmation, cancellation and unavailable steps.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** — state when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** — describe relevant edge cases and their exact outcomes.
- **Reason** — record the brief requirement or team decision behind the rule.
- **Verification** — link to the test or name the test case that checks it.
### Authenticated workspaces

Both frontends restore the server session on load and on window focus. Rule lists and details display the creator, including System for historical rules. Control Panel directs guests to login before opening the rule wizard. Its Teams view opens the assigned team directly for a leader and shows membership controls for that team. Members see the roster. Browser checks cover both roles, persistence across refresh, logout, expired sessions and rule ownership.

### Authoring runbooks

In Control Panel, signed-in members and leaders create and edit shared runbooks in Instructions, Actions and Review. The editor retains source whitespace for text, Markdown and YAML, displays YAML syntax errors with line numbers, and previews Markdown with raw HTML disabled. The action catalog supplies parameters and execution modes. Editing preserves existing inline Python settings. Opening a legacy URL proposes text with the original link. A name collision or a concurrent edit keeps the draft available. The runbook editor browser scenarios verify the form, API and alert snapshots in Event Monitor.

### Create a runbook while creating a rule

The Response section can attach an existing runbook or create one in the same rule submission. A Python Script step starts with human approval enabled. Choosing automatic execution allows the worker to run that step when its predecessors succeed. Both alert views show the persisted result and allow an operator to confirm a pending step. The inline-python browser scenario covers both execution modes and output chaining.

### Live alert delivery in open browser views

Control Panel and Event Monitor subscribe to the monitor SSE feed so their alert lists update as snapshots arrive. Both also subscribe to the signed-in recipient’s notification feed. A new UI delivery raises a dismissible notice even outside the Alerts view. The initial personal inbox is a baseline and does not raise historical notices. The browser reconciles the inbox again when the stream reconnects.

### Notification channels in the rule wizard

Configure the alert offers SMS, Email and UI next to the recipients. A new draft starts with UI selected. Choose one or more channels and check the selection in Review before saving.
