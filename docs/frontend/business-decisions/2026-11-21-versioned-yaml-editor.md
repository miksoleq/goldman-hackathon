# versioned yaml editor

Date 2026-11-21. [Pull request](https://github.com/miksoleq/goldman-hackathon/pulls?q=is%3Apr+head%3Afeat/rule-yaml-editor).

**Context**. Operators need to edit complete rule documents, including comments, and may open the same rule concurrently.

**Decision**. Store the submitted source with every revision and condition each update on the version loaded by the editor. Keep the local draft available after a rejected save.

**Rationale**. Preserving the source supports round trips through the editor. A version check prevents an older draft from overwriting another operator’s changes.

**Impact**. Successful changes update Engine through the existing outbox. Operators with a stale draft can copy their changes and reopen the latest version before retrying.
