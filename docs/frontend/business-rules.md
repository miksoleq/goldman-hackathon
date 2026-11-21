# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when the expected behavior or contract changes. Use Git history to trace earlier decisions.

Document a change in the same commit as the implementation and the focused test that verifies it. For a bug fix that restores an existing rule, capture the failing case in a regression test.

## Current rules

### Event Monitor reads current system data

- **Expected behavior** — The React dashboard in the separate frontend service reads the newest 100 event envelopes from the configured Redis stream, up to 100 rules and runbooks from Control's database, and up to 100 persisted alert inbox records, newest first. The four feeds are available through GET /api/monitor/messages, GET /api/monitor/rules, GET /api/monitor/runbooks, and GET /api/monitor/alerts.
- **Exceptions and boundaries** — Empty feeds return an empty array. A failed feed shows a retry action while other feeds remain usable. The dashboard is read-only.
- **Reason** — The dashboard exposes actual Engine and Control data and reads the durable alert inbox owned by Control.
- **Verification** — MonitorControllerIT verifies all four read endpoints using Redis and PostgreSQL. React API client tests cover feed routing and malformed or failed responses. Playwright CLI opened the dashboard; section interaction and mobile checks remain unverified.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** — state when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** — describe relevant edge cases and their exact outcomes.
- **Reason** — record the brief requirement or team decision behind the rule.
- **Verification** — link to the test or name the test case that checks it.