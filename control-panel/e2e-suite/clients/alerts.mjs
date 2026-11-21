import { randomUUID } from "node:crypto";

export class AlertsClient {
  constructor(request, control, engine) {
    this.request = request;
    this.control = control;
    this.engine = engine;
  }

  async createRule() {
    const tag = "Python E2E " + randomUUID();
    const response = await this.request.post(this.control + "/api/rules", { data: {
      name: tag, title: tag, route: { teams: ["demo-operations"] },
      match: { path: "payload.probe", op: "eq", value: tag },
      dedup: { keys: ["payload.probe"], windowSec: 60 },
      runbook: "script-runner-diagnostics"
    } });
    if (response.status() !== 201) throw Error("Rule creation failed: " + await response.text());
    return { tag, id: (await response.json()).id };
  }

  async ingest(tag) {
    const response = await this.request.post(this.engine + "/ingest", { data: { probe: tag } });
    if (response.status() !== 202) throw Error("Event ingestion failed: " + await response.text());
  }

  async detail(alertId) {
    const response = await this.request.get(this.control + "/api/alerts/" + alertId);
    if (!response.ok()) throw Error("Could not read alert: " + response.status());
    return response.json();
  }
}
