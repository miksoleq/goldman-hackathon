# Git Workflow

## Parallel work

- Before splitting work, agree on ownership, the shared interface, and one example input and expected output. Coordinate changes to that interface before editing. If two agent sessions share a laptop, use separate worktrees and branches.
- Start short-lived branches from `main`, for example `feat/import-sample-data` or `fix/demo-startup`. Keep each PR focused on one change and integrate the first working flow early.

## Review and merge

- A PR should say what changed, how to verify it, and whether it changes a shared contract or setup. Review the diff for correctness and missing tests for behavior critical to the demo. Put blocking findings first and cite the relevant lines.
- Once CI is configured, run the build and focused tests on PRs and on updates to `main`. Cancel obsolete runs for the same PR if supported. Check the CI result before merging and use squash merges when available.
- Keep `main` runnable. Run the demo smoke check after each merge once there is a working demo flow. Use the last rehearsed commit for a presentation fallback if a late change breaks the flow.
