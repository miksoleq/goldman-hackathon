#!/usr/bin/env python3
"""Verify live automatic selection on a disposable Control/Engine/PostgreSQL stack."""
from demo_session import login_cookie
import argparse
import json
import subprocess
import time
from urllib.request import Request, urlopen
from uuid import uuid4


def request(url, body=None, cookie=None):
    data = json.dumps(body).encode() if body is not None else None
    req = Request(url, data=data, headers={"Content-Type": "application/json"})
    if cookie:
        req.add_header("Cookie", cookie)
    with urlopen(req, timeout=5) as response:
        return json.load(response)


def verify(engine, control, pg_container):
    # This check leaves fixtures in the explicitly supplied test stack for inspection.
    run = str(uuid4())
    team, book_name = "ops-" + run, "guide-" + run

    def sql(statement):
        subprocess.run(["docker", "exec", "-i", pg_container, "sh", "-c",
                        'psql -X -q -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-${POSTGRES_USER:-postgres}}"'],
                       input=statement, text=True, check=True)

    sql(f"""INSERT INTO teams(name) VALUES ('{team}');
            INSERT INTO runbooks(name,format,content,matches) VALUES
            ('{book_name}','text','Old: {{{{payload.service}}}}',
             '{{"path":"payload.run","op":"eq","value":"{run}"}}');""")
    rule = request(control + "/api/rules", {"name": "auto-" + run, "route": {"teams": [team]},
                   "match": {"path": "payload.run", "op": "eq", "value": run}, "title": "Incident",
                   "runbook": "auto", "dedup": {"keys": ["payload.probe"]}}, cookie=login_cookie(control))["id"]

    def await_selection(expected):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            request(engine + "/ingest", {"run": run, "service": "orders", "probe": str(uuid4())})
            time.sleep(.2)
            for alert in request(control + "/api/monitor/alerts"):
                if alert["ruleId"] == rule and (alert.get("runbook") or {}).get("content") == expected:
                    return request(control + "/api/alerts/" + alert["alertId"])
        raise AssertionError(f"Expected runbook content {expected!r}")

    original = await_selection("Old: orders")
    assert original["runbook"]["name"] == book_name
    sql(f"UPDATE runbooks SET content='New: {{{{rule.name}}}}' WHERE name='{book_name}'")
    updated = await_selection("New: auto-" + run)
    assert updated["runbookId"] == original["runbookId"]
    sql(f"UPDATE runbooks SET enabled=false WHERE name='{book_name}'")
    # New alerts with no runbook can also exist during initial propagation. Only probe a new event ID.
    deadline = time.monotonic() + 20
    disabled_ids = set()
    while time.monotonic() < deadline:
        disabled_ids.add(request(engine + "/ingest", {"run": run, "probe": str(uuid4())})["eventId"])
        time.sleep(.2)
        if any(a["eventId"] in disabled_ids and a["ruleId"] == rule and a.get("runbook") is None
               for a in request(control + "/api/monitor/alerts")):
            break
    else:
        raise AssertionError("Disabled runbook still selected")
    reread = request(control + "/api/alerts/" + original["alertId"])
    assert reread["runbook"] == original["runbook"]
    print(f"PASS alert {original['alertId']} still contains the original instruction after an edit and disable")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True)
    parser.add_argument("--control", required=True)
    parser.add_argument("--postgres-container", required=True)
    args = parser.parse_args()
    verify(args.engine.rstrip("/"), args.control.rstrip("/"), args.postgres_container)
