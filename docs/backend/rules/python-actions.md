# Python runbook actions

Use the seeded `script-runner-diagnostics` runbook to call the runner's health endpoint and approve a script that summarizes its response. The first step returns `{"status":"UP"}`. The approved step returns `{"service":"script-runner","healthy":true}`.

## Authoring

Control accepts a signed-in user's YAML or JSON at `POST /api/apply`. A successful creation returns HTTP 201. A duplicate name returns 409. Create a reusable script with an ActionType resource.

```yaml
kind: ActionType
name: summarize-health
spec:
  type: python
  code: |
    def run(ctx):
        return {"healthy": ctx["previousStepResult"]["status"] == "UP"}
```

A Runbook resource accepts named and inline actions. The same steps are available under a rule's inline `runbook`. Existing `actions` arrays accept the same entries alongside their existing `type` and `params` form. Script source is stored literally.

```yaml
kind: Runbook
name: inspect-runner
spec:
  steps:
    - action: http_get
    - action: summarize-health
    - action: python
      approval: required
      timeoutMs: 3000
      code: |
        def run(ctx):
            event = ctx["event"]
            alert = ctx["alert"]
            previous = ctx["previousStepResult"]
            print(alert["title"])
            return {"healthy": previous["healthy"], "adapter": event["meta"]["adapter"]}
```

The event contains the Engine's original `payload` and `meta`. Historical envelopes without a captured event supply JSON null. The alert contains the current alert snapshot. The first step receives null for `previousStepResult`. Later steps receive the preceding HTTP response body, Python return value, or structured action outcome. Set `approval: none` explicitly on a Python ActionType or step to allow automatic execution.

## Alert detail and execution

Use `GET /api/alerts/{alertId}` and the existing `actions` array. Each Python step exposes `kind: "python"`, its catalog name in `type`, the exact `code`, and `approvalRequired`. A pending step becomes `available` after its predecessors succeed. Send `{"confirmed":true}` with the current session to `POST /api/alerts/{alertId}/actions/{stepNo}/execute`. Anonymous execution returns 401. Missing confirmation or an unfinished predecessor returns 409.

The POST response and subsequent alert detail expose the same step. Display `executedBy` and `approvedBy` with its output. A successful script produces a step like this excerpt.

```json
{
  "kind": "python",
  "status": "succeeded",
  "approvalRequired": true,
  "executedBy": "demo.member",
  "approvedBy": "demo.member",
  "result": {
    "status": "SUCCESS",
    "exitCode": 0,
    "result": {"healthy": true},
    "stdout": "",
    "stderr": "",
    "durationMs": 42
  }
}
```

A script exception or deadline produces a failed step with `ERROR` or `TIMEOUT` in `step.result.status`. Subsequent steps are skipped. A runner transport failure has `exitCode: null` and an explanation in `stderr`.

## Execution environment

Only Control and the runner share the internal Compose network. Each request starts a fresh isolated interpreter and supplies its context through stdin. The child installs memory and CPU limits and a Linux seccomp syscall allowlist before compiling the script. Scripts can compute in memory with preloaded modules including `json`, `re`, `math`, `statistics`, `collections`, `datetime` and `decimal`. The kernel blocks opening files, opening sockets and creating processes. Imports that require reading additional files are unavailable.

The runner accepts one active job within its 256 MiB container budget. The child has a 96 MiB address-space limit and a wall deadline of three seconds by default, capped at five seconds. Each output stream retains up to 16 KiB, and the JSON result can occupy up to 64 KiB. A script with no `run` function returns null. Control's HTTP deadline is six seconds.
