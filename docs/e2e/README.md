# E2E Testing for the Hackathon

Use end-to-end tests to prove that the most important user journey works from entry point to a result the judges can see. Keep the setup small and adapt these rules to the stack chosen for the challenge.

## What to cover

- Start with one happy path that demonstrates a judging criterion. Add a small number of smoke or failure cases only when they protect an important promise.
- Exercise the real user-facing flow. Use an API or fixture for setup when that makes the test faster and more reliable, then assert the outcome a user would observe.
- Keep the test readable as a sequence of actions and expected outcomes. Add a page object or shared helper when it removes repeated UI details; do not introduce framework layers or shared test modules without a concrete need.
- Prefer isolated, uniquely named test data. Clean up data created by a test when practical. Start tests sequentially if they share state; add parallel execution only after isolation is clear.
- Keep environment URLs and credentials out of source control. Document the verified command and any required services in [AGENTS.md](../../AGENTS.md#build-run--test-commands).

## Stable UI selectors

- Prefer accessible, user-facing selectors: role and name, label, placeholder, or visible text. Use a stable test ID when the UI has no suitable accessible selector.
- Use XPath only when a semantic locator or stable test ID cannot identify the element. Keep it short and anchored to stable attributes or text; avoid absolute paths and positional indexes.
- Inspect the rendered page before writing a selector. The [Playwright CLI](https://github.com/microsoft/playwright-cli) can snapshot a page, inspect element attributes, and generate Playwright locators. Its `generate-locator` command outputs a Playwright locator, not necessarily XPath. You can inspect the DOM with `eval` or `run-code` to derive an XPath when needed, then verify that it is unique and stable. See [Playwright locator guidance](https://playwright.dev/docs/locators).

## Reliability and feedback

- Do not use fixed sleeps. Wait for a meaningful state, such as an element becoming visible or a result appearing, with a bounded timeout.
- On failure, keep the smallest useful diagnostics: a screenshot and, when the runner supports it, a trace or browser log. Avoid committing generated reports or credentials.
- Keep the demo runnable from a fresh checkout and retain a rehearsed fallback if the test environment is unreliable.

## Reports and CI

- Make the test's pass/fail result and failure evidence easy to find. Add [Allure visual reports](https://allurereport.org/docs/visual-analytics/) when the selected runner already supports them and the setup is quick; its charts can make a demo report clearer. Trend charts need run history, so treat them as an optional extra rather than a first-run requirement.
- Add a CI smoke run when it is straightforward and repeatable. Do not make a large pipeline, parallel grid, cross-environment matrix, or reporting service a prerequisite for the demo.
