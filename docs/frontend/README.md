# Frontend Guidelines

Build the smallest interface that clearly demonstrates the agreed user journey. Choose tools the team can run and debug within the hackathon. These rules apply to AI-generated code too: verify the running application and report what was actually checked.

## Design and delivery workflow

- Agree on one demo journey and an example API request and response before splitting frontend and backend work.
- Build a quick, clickable prototype of the main screen in code. Use an existing visual reference when helpful; keep the prototype focused on the demo.
- Once the visual direction is chosen, record a few decisions in `DESIGN.md`: colors, typography, spacing, and the style of shared controls. Update it as the design evolves.
- Integrate the main screen with the real API as early as possible and verify the complete journey. Expand to other screens only when they support the demo.
- Open the running app with Playwright CLI, inspect screenshots at desktop and narrow mobile widths, exercise the key interactions, and fix visual or functional problems found. A screenshot alone does not verify the API or business result; check the full flow too.

## Code and structure

- Keep components small and named for their purpose. Separate rendering, reusable UI, and API calls when it makes the code easier to follow; avoid building a framework for a single screen.
- Use the project's existing language, framework, package manager, and conventions. Add dependencies only when they solve a concrete problem. Commit the appropriate lockfile, but not installed dependencies or build output.
- Keep business decisions and authorization on the server. Frontend validation improves feedback; the server must validate and authorize independently.
- Configure the API base URL per environment. Never put secrets in frontend code, client environment variables, browser storage, logs, or demo data: client code and configuration are visible to users.

## Behavior and data

- Implement the agreed API contract and use real responses for the demo flow. Do not present placeholders, invented results, or hard-coded success as real behavior.
- Handle loading, empty, success, validation error, and request failure states where relevant. Explain errors in plain language and let users retry when appropriate. Do not reveal stack traces or sensitive data.
- Prevent accidental duplicate submissions while a request is pending. Keep entered values when a recoverable request fails.
- Validate inputs and show useful messages near the affected fields. Handle slow or failed requests and malformed or unexpected responses without leaving a broken screen.
- Clean up event listeners and timers when components are removed. Avoid unnecessary requests or state updates; handle stale responses when a newer request supersedes them.

## Usability and accessibility

- Use semantic HTML: buttons for actions, links for navigation, headings for structure, and labels associated with form inputs. Give icon-only buttons accessible names; provide meaningful alt text for informative images.
- Make the main journey usable with keyboard alone. Show visible focus, use a logical tab order, and ensure dialogs can be closed and focus is handled appropriately.
- Use readable text, sufficient contrast, and layouts that work at desktop and narrow mobile widths. Do not rely on color alone to convey status.
- Escape or render user-supplied text safely using the framework's normal mechanisms. Avoid inserting untrusted HTML; if rich HTML is truly needed, sanitize it with a maintained library.

## Make E2E tests reliable

- Give controls stable, descriptive labels and accessible names. Keep visible loading, error, and success states so tests can wait for and check real outcomes.
- Prefer semantic elements and stable identifiers over selectors tied to CSS classes, DOM position, or generated IDs. Add a descriptive, stable `data-testid` when semantic selection cannot uniquely identify an important element; do not put sensitive data in it.
- Preserve these identifiers and names unless the user-facing behavior changes. See the E2E repository's guidance for using Playwright CLI to inspect the UI and build Page Objects.

## Before handing off a frontend change

1. Run the project's documented install, build, and relevant checks. Record verified commands and prerequisites in [AGENTS.md](AGENTS.md#build-run--test-commands); do not claim checks passed if they were not run.
2. Start the app and follow the actual demo journey with representative data, including at least one relevant failure or empty state. Confirm the displayed result matches the expected API response.
3. Check keyboard navigation and the screen at a desktop and a narrow viewport. Inspect the browser console and network failures; investigate errors affecting the flow.
4. Run the focused E2E smoke test when available. If it does not exist yet, document the manual steps and expected result, then add an automated test when the flow is stable.
5. Report what changed, what was checked, failures or gaps, and exact steps needed to reproduce the demo. These checks reduce risk but cannot guarantee the absence of bugs.
