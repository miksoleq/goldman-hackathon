"""Exercise Control, Engine, Redis, PostgreSQL and script-runner in a running demo stack."""
import http.cookiejar
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener

CONTROL = os.environ.get("CONTROL_URL", "http://localhost:8082")
ENGINE = os.environ.get("ENGINE_URL", "http://localhost:8081")
session = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(url, body=None, client=session):
    data = None if body is None else json.dumps(body).encode()
    request = Request(url, data, {"Content-Type": "application/json"})
    with client.open(request, timeout=10) as response:
        return json.load(response)


def expect_rejection(status, url, body, client=session):
    try:
        call(url, body, client)
    except HTTPError as error:
        assert error.code == status, (error.code, status)
        return
    raise AssertionError(f"Expected HTTP {status}")


def wait_for(check):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if value := check():
            return value
        time.sleep(0.3)
    raise AssertionError("Timed out waiting for alert or step")


def make_alert(runbook):
    tag = str(uuid.uuid4())
    created = call(CONTROL + "/api/rules", {"name": "Python smoke " + tag, "route": {"teams": ["demo-operations"]},
                   "title": "Diagnostic", "match": {"path": "payload.test", "op": "eq", "value": tag}, "runbook": runbook})

    def find_alert():
        # Rule publication and alert delivery are asynchronous.
        call(ENGINE + "/ingest", {"test": tag, "service": "payments"})
        return next((row["alertId"] for row in call(CONTROL + "/api/monitor/alerts")
                     if row["ruleId"] == created["id"]), None)

    return CONTROL + "/api/alerts/" + wait_for(find_alert)


def main():
    call(CONTROL + "/api/auth/login", {"username": "demo.member", "password": "Runbook123!"})
    alert = make_alert("script-runner-diagnostics")
    wait_for(lambda: call(alert)["actions"][1]["available"])
    steps = call(alert)["actions"]
    assert steps[0]["result"]["response"] == {"status": "UP"}
    assert steps[1]["kind"] == "python" and steps[1]["status"] == "pending_approval"
    execute = alert + "/actions/2/execute"
    expect_rejection(401, execute, {"confirmed": True}, build_opener())
    expect_rejection(409, execute, {"confirmed": False})
    summary = call(execute, {"confirmed": True})
    assert summary["result"]["result"] == {"service": "script-runner", "healthy": True}, summary
    assert summary["executedBy"] == summary["approvedBy"] == "demo.member"
    assert call(alert)["actions"][1] == summary

    name = "python-smoke-" + str(uuid.uuid4())
    call(CONTROL + "/api/apply", {"kind": "ActionType", "name": name, "spec": {"type": "python", "code":
         "def run(ctx):\n    print('checking')\n    return [ctx['alert']['title'], ctx['event']['payload']['service'], ctx['previousStepResult']]"}})
    book = call(CONTROL + "/api/apply", {"kind": "Runbook", "name": name, "spec": {"steps": [
        {"action": name}, {"action": "python", "approval": "none", "code":
            "def run(ctx): return ctx['previousStepResult'][1]"},
        {"action": "python", "timeoutMs": 400, "approval": "none", "code": "while True: pass"},
        {"action": "python", "code": "pass"}]}})
    alert = make_alert(book["name"])
    step = call(alert + "/actions/1/execute", {"confirmed": True})
    assert step["result"]["result"] == ["Diagnostic", "payments", None], step
    assert step["result"]["stdout"] == "checking\n" and step["result"]["durationMs"] > 0
    wait_for(lambda: call(alert)["actions"][-1]["status"] == "skipped")
    steps = call(alert)["actions"]
    assert steps[1]["result"]["result"] == "payments"
    assert steps[2]["status"] == "failed" and steps[2]["result"]["status"] == "TIMEOUT"
    assert steps[2]["executedBy"] == "system" and steps[2]["approvedBy"] is None

    alert = make_alert({"name": name + "-error", "steps": [
        {"action": "python", "code": "raise ValueError('diagnostic failed')"}]})
    step = call(alert + "/actions/1/execute", {"confirmed": True})
    assert step["result"]["status"] == "ERROR" and "diagnostic failed" in step["result"]["stderr"]
    assert call(alert)["actions"][0] == step
    print("Passed seeded health check, approval, named and inline scripts, chaining, timeout and error history.")


if __name__ == "__main__":
    main()
