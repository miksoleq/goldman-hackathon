# Goldman Alerting Control Panel

A standalone React interface for Control rule creation and system monitoring. It lives alongside the existing Event Monitor in its own folder and uses the same Goldman Sachs navy and blue design direction.

## Run locally

Requirements are Node.js 24 and npm 11. Start the Control service and its PostgreSQL and Redis dependencies from the repository root with `docker compose up -d control`. Then run:

```powershell
cd control-panel
npm ci
npm run dev
```

Open `http://localhost:5174`. Vite proxies `/api` requests to Control on port 8082.

To run the panel with Docker Compose instead, run `docker compose up --build -d` from the repository root and open `http://localhost:8083`. Set `CONTROL_PANEL_PORT` to change its host port.

## Checks

Run `npm test` and `npm run build` from this folder. With the Docker demo and Chrome running locally, `npm run test:e2e` exercises the browser flows. Set `PANEL_URL` or `CHROME_BIN` to override the panel origin or browser executable. The ownership scenario creates a uniquely named demo rule.

The UI reads the existing `/api/monitor/{rules,messages,alerts,runbooks}` endpoints and creates rules through `POST /api/rules`. The recipient pickers use Control’s team and user directories. Log in as a demo Member to create a rule or as a Team Leader to manage membership in Teams. Both roles can inspect rule ownership.

## Runbook editor

Open **Runbooks**, sign in, then select **Create runbook** or **Edit**. Choose text, Markdown or YAML, add any catalog actions in execution order, and review before saving. To see the update in Event Monitor, attach the runbook to a rule and generate a new alert after Engine receives the rule update. Earlier alerts retain their original instructions.

The runbook browser scenarios use both web apps, Control, Engine, PostgreSQL and Redis with rule distribution enabled. `ENGINE_PORT` and `MONITOR_PORT` override the default ports 8081 and 8080 on the panel host.
