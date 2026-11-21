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

Run `npm test` and `npm run build` from this folder.

The UI reads the existing `/api/monitor/{rules,messages,alerts,runbooks}` endpoints and creates rules through `POST /api/rules`. Team and username recipients are entered by their exact Control names because the current API does not expose recipient catalogs.
