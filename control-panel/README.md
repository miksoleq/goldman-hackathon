# Goldman Alerting Control Panel

A standalone React interface for Control rule creation and system monitoring. It lives alongside the existing Event Monitor in its own folder and uses the same Goldman Sachs navy and blue design direction.

## Run locally

Requirements are Node.js 24 and npm 11. Start the services from the repository root with `docker compose up -d control engine`. Then run:

```powershell
cd control-panel
npm ci
npm run dev
```

Open `http://localhost:5174`. Vite proxies the snapshot endpoint to Engine on port 8081 and other API requests to Control on port 8082. Set `ENGINE_URL` and `CONTROL_URL` to use other addresses.

To run the panel with Docker Compose instead, run `docker compose up --build -d` from the repository root and open `http://localhost:8083`. Set `CONTROL_PANEL_PORT` to change its host port.

## Checks

Run `npm test` and `npm run build` from this folder.

Open **Create rule → Write YAML** to submit a document. Select a saved rule and choose **Edit YAML** to reopen its original source. If another operator saves first, copy any changes you want to keep, cancel, and reopen the latest version.

The Playwright scenario in `e2e/yaml-rule.js` accepts an open panel page and an optional Engine URL. Run it against a disposable stack with the `payments` and `finance-ops` teams. It exercises create, validation, concurrent edits and payment ingestion. The resulting alert must include the updated title, the default severity and the rendered body.

Both the panel and Event Monitor subscribe to `/api/monitor/alerts/live`. Opening a tab loads the current alerts. A new alert displays a notification and subsequent matches update its count. The browser reconnects automatically and receives the latest persisted inbox after reconnecting. Use **Log in → Demo Member** to create or edit rules in the local demo.

The Playwright scenario `e2e/live-alert.js` takes a panel page from a browser context, the Engine URL, and the Event Monitor URL. It signs in, ingests an event and verifies delivery to both open tabs and a deduplicated count update.
