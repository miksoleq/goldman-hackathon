"""Local delivery latency benchmark. Run via scripts/test-e2e.sh --latency."""
import csv
import json
import math
import os
import subprocess
from contextlib import contextmanager
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import HTTPCookieProcessor, Request, build_opener


def api(client, url, body=None, method=None):
    data = None if body is None else json.dumps(body).encode()
    req = Request(url, data, {"Content-Type": "application/json"}, method=method)
    with client.open(req, timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def percentile(values, probability):
    return sorted(values)[math.ceil(len(values) * probability) - 1]


@contextmanager
def ui_observer():
    proc = subprocess.Popen(["node", "control-panel/e2e-suite/clients/latency-ui.mjs"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        if proc.stdout.readline().strip() != "ready":
            raise RuntimeError("Browser observer failed to open both Alerts views")

        def snapshot(command="observe"):
            proc.stdin.write(command + "\n")
            proc.stdin.flush()
            return json.loads(proc.stdout.readline())

        yield snapshot
    finally:
        proc.stdin.close()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()


def mark_observed(seen, titles, submitted, observed):
    for title in titles:
        if title in submitted:
            seen.setdefault(title, observed)


def measure(name, total, interval, tag, engine, probes, browser=None):
    tokens = [f"{tag}-{name}-{i:03}" for i in range(total)]
    seen = {channel: {} for channel in probes}
    if browser:
        seen.update(panel={}, monitor={})

    def send(token):
        client = build_opener()
        start = time.perf_counter()
        reply = api(client, engine + "/ingest", {"batch": tag, "token": token})
        return start, reply["eventId"]

    with ThreadPoolExecutor(max_workers=8) as pool:
        start = time.perf_counter()
        futures = {}
        while time.perf_counter() < start + interval * total + 40:
            while len(futures) < total and time.perf_counter() >= start + len(futures) * interval:
                token = tokens[len(futures)]
                futures[token] = pool.submit(send, token)
            for channel, probe in probes.items():
                found = probe()
                observed = time.perf_counter()
                mark_observed(seen[channel], found, futures, observed)
            if browser:
                snapshot = browser()
                observed = time.perf_counter()
                for channel, titles in snapshot.items():
                    mark_observed(seen[channel], titles, futures, observed)
            if all(len(items) == total for items in seen.values()):
                break
            time.sleep(0.025)

    rows = []
    for token in tokens:
        sent, event_id = futures[token].result()
        for channel, items in seen.items():
            duration = round((items[token] - sent) * 1000, 2) if token in items else None
            rows.append((name, token, event_id, channel, duration))
    return rows


def main():
    control, engine, mail, sms = (os.environ[key] for key in
                                 ("CONTROL_URL", "ENGINE_URL", "MAILPIT_URL", "SMS_MOCK_URL"))
    session = build_opener(HTTPCookieProcessor(CookieJar()))
    observer = build_opener()
    username = os.environ["E2E_USERNAME"]
    api(session, control + "/api/auth/login", {"username": username, "password": os.environ["E2E_PASSWORD"]})
    tag = "perf-" + uuid.uuid4().hex[:16]
    address, phone = tag + "@example.test", "+12025550123"
    for channel, contact in (("sms", phone), ("email", address)):
        api(session, control + "/api/me/notification-contacts/" + channel, {"address": contact}, "PUT")
    api(session, control + "/api/me/notification-preferences",
        {c: {"enabled": True, "minSeverity": "info"} for c in ("ui", "sms", "email")}, "PUT")
    rule = api(session, control + "/api/rules", {
        "name": tag, "route": {"users": [username]}, "match": {"path": "payload.batch", "op": "eq", "value": tag},
        "dedup": {"keys": ["payload.token"], "windowSec": 120}, "title": "{{payload.token}}", "runbook": "none"})
    for _ in range(200):
        statuses = api(observer, engine + "/api/engine/snapshot")
        if any(r["ruleId"] == rule["id"] and r["state"] == "active" for r in statuses):
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("Rule did not activate")

    probes = {
        "inbox": lambda: [r["title"] for r in api(session, control + "/api/me/notifications")["items"]],
        "email": lambda: [m["Subject"] for m in api(observer, mail + "/api/v1/messages?limit=100")["messages"]
                          if any(to["Address"] == address for to in m["To"])],
        "sms": lambda: [m["text"].split(" ", 1)[0] for m in api(observer, sms + "/messages") if m["to"] == phone],
    }
    warmup = measure("warmup", 1, 0, tag, engine, probes)
    if any(row[-1] is None for row in warmup):
        raise RuntimeError("Warm-up delivery failed")
    with ui_observer() as browser:
        records = []
        for name, interval in (("steady", 0.5), ("burst", 0)):
            rows = measure(name, 30, interval, tag, engine, probes, browser)
            records.extend(rows)
            for channel in (*probes, "panel", "monitor"):
                latency = [ms for _, _, _, c, ms in rows if c == channel and ms is not None]
                summary = (f"p50={percentile(latency, .5):.0f} p95={percentile(latency, .95):.0f} "
                           f"max={max(latency):.0f} ms") if latency else "no receipts"
                print(f"{name:7} {channel:6} {len(latency)}/30  {summary}", flush=True)
        # Validate missing automatic receipts independently, after both timed profiles.
        missing = {(channel, token) for _, token, _, channel, ms in records
                   if channel in ("monitor", "panel") and ms is None}
        if missing:
            browser("refresh")
            remaining = missing.copy()
            deadline = time.perf_counter() + 5
            while remaining and time.perf_counter() < deadline:
                rendered = browser()
                remaining = {(c, t) for c, t in remaining if t not in rendered[c]}
                time.sleep(.05)
            print(f"Manual refresh diagnostic: {len(missing) - len(remaining)}/{len(missing)} missing UI receipts visible")
    path = Path("e2e-results") / (tag + ".csv")
    path.parent.mkdir(exist_ok=True)
    with path.open("w", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(("profile", "token", "event_id", "channel", "observed_latency_ms"))
        writer.writerows(records)
    print(path)
    if any(row[-1] is None for row in records):
        raise SystemExit("Missing receipts")


if __name__ == "__main__":
    main()
