# E2E Testing for the Hackathon

Use end-to-end tests to prove that the most important user journey works from entry point to a result the judges can see. Keep the setup small and adapt these rules to the stack chosen for the challenge.

## What to cover

- Start with one happy path that demonstrates a judging criterion. Add a small number of smoke or failure cases only when they protect an important promise.
- Exercise the real user-facing flow. Use an API or fixture for setup when that makes the test faster and more reliable, then assert the outcome a user would observe.
- Keep the test readable as a sequence of actions and expected outcomes. Add a page object or shared helper when it removes repeated UI details; do not introduce framework layers or shared test modules without a concrete need.
- Prefer isolated, uniquely named test data. Clean up data created by a test when practical. Start tests sequentially if they share state; add parallel execution only after isolation is clear.
- Keep environment URLs and credentials out of source control. Document the verified command and any required services in [AGENTS.md](../../AGENTS.md#build-run--test-commands).

## Use Playwright CLI to build Page Objects

- Use the [Playwright CLI](https://github.com/microsoft/playwright-cli) as a browser exploration tool while creating or updating Page Objects: open the app, inspect snapshots, try a candidate locator, and inspect the element's attributes.
- Turn what you learn into a stable locator and an intent-based Page Object method in the E2E framework chosen for the project. Snapshot refs such as `e5` are temporary exploration handles; never commit them as selectors.
- Example exploration commands:
  ```sh
  playwright-cli open <app-url>
  playwright-cli snapshot
  playwright-cli generate-locator e5 --raw
  playwright-cli eval "el => el.getAttribute('data-testid')" e5
  playwright-cli close
  ```
- The CLI is useful even if the project uses another test runner: translate the inspected element into that runner's locator syntax. If an XPath is required, derive a short relative XPath from stable attributes or text and verify that it uniquely identifies the element.
- `generate-locator` generates a Playwright locator, not a guaranteed XPath. Use role, label, text, or test-ID locators where the chosen framework supports them; use XPath only when those options do not fit. See [Playwright locator guidance](https://playwright.dev/docs/locators).

## Stable UI selectors

- Prefer accessible, user-facing selectors: role and name, label, placeholder, or visible text. Use a stable test ID when the UI has no suitable accessible selector.
- Keep XPath short and anchored to stable attributes or text. Avoid absolute paths and positional indexes.

## Reliability and feedback

- Do not use fixed sleeps. Wait for a meaningful state, such as an element becoming visible or a result appearing, with a bounded timeout.
- On failure, keep the smallest useful diagnostics: a screenshot and, when the runner supports it, a trace or browser log. Avoid committing generated reports or credentials.
- Keep the demo runnable from a fresh checkout and retain a rehearsed fallback if the test environment is unreliable.

## Reports and CI

- Make the test's pass/fail result and failure evidence easy to find. Add [Allure visual reports](https://allurereport.org/docs/visual-analytics/) when the selected runner already supports them and the setup is quick; its charts can make a demo report clearer. Trend charts need run history, so treat them as an optional extra rather than a first-run requirement.
- Add a CI smoke run when it is straightforward and repeatable. Do not make a large pipeline, parallel grid, cross-environment matrix, or reporting service a prerequisite for the demo.
