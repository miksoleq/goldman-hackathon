# Git Workflow

## Everyday pushes

- Push small, working changes directly to `main`. Pull before starting a batch of work. If a push is rejected because `main` moved, integrate your teammate's changes and retry with a regular push.
- Agree on ownership, the shared interface, and one example input and expected output before splitting work. Use a short-lived branch for experiments or unfinished changes that would break `main`. Separate worktrees can help when running parallel agent sessions on one laptop.

## Commit messages

- Group related changes into cohesive, logical commits that are easy to understand and review. Avoid a separate commit for each small edit; split unrelated work when it improves clarity. Keep each commit focused and working when practical.

- Use one short line in English in the form `<imperative verb> <what changed>`. For example, write `Add demo seed data`.
- Omit `Co-authored-by` trailers and any other authorship attribution for Claude or any other AI agent.

## Coordinate when needed

- Discuss changes to a shared interface or demo setup before editing. Use a PR when you want your teammate to review a risky change before it lands. If you open a PR, say what changed and how to verify it.
- Before opening every PR, add a business-decision note under [`docs/business-decisions/`](../business-decisions/). Follow [`TEMPLATE.md`](../business-decisions/TEMPLATE.md), use a descriptive filename such as `YYYY-MM-DD-short-topic.md`, and summarize the business decisions made for the change, including their rationale and impact. If the change required no business decisions, record that explicitly. Link the note from the PR description.
- Run a focused local check for behavior changes and smoke-test the demo when its flow changes. If the team adds CI, start with one fast check and cancel obsolete runs when supported. Keep the last rehearsed commit available as a demo fallback.
