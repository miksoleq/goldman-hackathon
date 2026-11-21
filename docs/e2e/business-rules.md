# Business Rules

Use this file for current business behavior and agreed contracts between components. Update an entry when the expected behavior or contract changes. Use Git history to trace earlier decisions.

Document a change in the same commit as the implementation and the focused test that verifies it. For a bug fix that restores an existing rule, capture the failing case in a regression test.

## Current rules

Add entries here as the team agrees on the expected behavior after reading the challenge brief.

## Rule template

Use one subsection per rule with a short, descriptive heading.

- **Expected behavior** — state when the rule applies and give a concrete input and expected result.
- **Exceptions and boundaries** — describe relevant edge cases and their exact outcomes.
- **Reason** — record the brief requirement or team decision behind the rule.
- **Verification** — link to the test or name the test case that checks the rule.

### Runbook approval and alert deduplication

A matching event creates an alert with its runbook snapshot. The seeded diagnostic HTTP step completes before its Python step becomes available. The Python step requires confirmation and records the approving user. A repeated event in the deduplication window increments the existing alert count without creating another set of steps. The isolated browser scenario in `control-panel/e2e-suite` verifies this journey.
