#!/usr/bin/env python3
"""Exercise both packaged services using dedicated, disposable PostgreSQL and Redis."""
import json
import re
import subprocess
import tempfile
import time
from contextlib import ExitStack
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "backend" / "target"


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def eventually(check, description):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(.1)
    raise AssertionError(f"Timed out waiting for {description}")


def container(stack, port, *args):
    cid = command("docker", "run", "--rm", "-d", "-p", f"127.0.0.1::{port}", *args)
    stack.callback(subprocess.run, ["docker", "rm", "-f", cid], stdout=subprocess.DEVNULL)
    return cid, command("docker", "port", cid, str(port)).split(":")[-1]


def app(stack, tmp, module, *options):
    jar = ROOT / "backend" / module / "target" / f"{module}-0.1.0-SNAPSHOT.jar"
    log = tmp / f"{module}.log"
    output = stack.enter_context(log.open("w"))
    proc = subprocess.Popen(["java", "-jar", str(jar), "--server.port=0", "--rules.redis.enabled=true", *options],
                            stdout=output, stderr=subprocess.STDOUT)

    def stop():
        proc.terminate()
        proc.wait(timeout=20)
    stack.callback(stop)

    def started():
        if proc.poll() is not None:
            raise RuntimeError(log.read_text()[-3000:])
        return re.search(r"Tomcat started on port (\d+)", log.read_text())

    port = eventually(started, module).group(1)
    return f"http://127.0.0.1:{port}", log


def request(url, data=None, media="application/json", status=200):
    req = Request(url, data=data, headers={"Content-Type": media})
    try:
        response = urlopen(req, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        body = response.read()
        assert response.status == status, (response.status, url, body[:500])
        return json.loads(body)


def test_oom():
    BUILD.mkdir(parents=True, exist_ok=True)
    with ExitStack() as stack:
        tmp = Path(stack.enter_context(tempfile.TemporaryDirectory(dir=BUILD)))
        pg, pg_port = container(stack, 5432, "--tmpfs", "/var/lib/postgresql/data",
                                "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:17-alpine")
        _, redis_port = container(stack, 6379, "redis:7.4-alpine", "redis-server", "--save", "")
        db_url = f"jdbc:postgresql://127.0.0.1:{pg_port}/postgres"
        eventually(lambda: subprocess.run(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1"],
                                          stdout=subprocess.DEVNULL).returncode == 0, "PostgreSQL")
        control, _ = app(stack, tmp, "control", f"--spring.datasource.url={db_url}",
                         "--spring.datasource.username=postgres", f"--spring.data.redis.port={redis_port}")
        engine, engine_log = app(stack, tmp, "engine", f"--spring.data.redis.port={redis_port}",
                                 "--ingest.max-payload-bytes=64",
                                 "--logging.level.com.goldman.alerting.engine.ingest.redis=DEBUG")
        command("docker", "exec", pg, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-c",
                "INSERT INTO teams(name) VALUES ('oom-demo')")
        rule = request(control + "/api/rules", b"""
name: OOM
route: {teams: [oom-demo]}
match:
  all:
    - {path: meta.contentType, op: eq, value: text}
    - {path: payload.message, op: regex, value: '(?i)out of memory|oomkilled'}
severity: critical
title: "OOM w {{meta.source}}"
body: "{{payload.message | truncate: 300}}"
""", "application/yaml", 201)["id"]
        eventually(lambda: f"Indexed rule ruleId={rule} " in engine_log.read_text(), "Engine snapshot")

        for text, matches in [(b"OOMKilled pid 12", True), (b"OOMKilled pid 98", True), (b"healthy", False)]:
            event_id = request(engine + "/ingest/worker", text, "text/plain", 202)["eventId"]
            events = request(control + "/api/monitor/messages")
            event = next(item["envelope"] for item in events if item["envelope"]["eventId"] == event_id)
            assert (event["source"], event["contentType"], event["adapter"]) == ("worker", "text", "http")
            assert event["payload"] == {"message": text.decode()}
            assert event["matchedRuleIds"] == ([rule] if matches else [])

        json_id = request(engine + "/ingest", b'{"message":"OOMKilled pid 21"}', status=202)["eventId"]
        event = next(m["envelope"] for m in request(control + "/api/monitor/messages")
                     if m["envelope"]["eventId"] == json_id)
        assert (event["source"], event["contentType"], event["adapter"]) == ("http", "json", "http")
        assert event["matchedRuleIds"] == [] and event["payload"] == {"message": "OOMKilled pid 21"}
        request(engine + "/ingest", b'{"broken"', status=400)
        request(engine + "/ingest", b'x' * 65, "text/plain", 413)

        def grouped():
            alerts = [a for a in request(control + "/api/monitor/alerts") if a["ruleId"] == rule]
            return alerts[0]["alertId"] if len(alerts) == 1 and alerts[0]["count"] == 2 else None

        alert_id = eventually(grouped, "one persisted alert with count=2")
        alert = request(control + "/api/alerts/" + alert_id)
        assert (alert["count"], alert["title"], alert["body"]) == (2, "OOM w worker", "OOMKilled pid 12")
        print(f"PASS OOM rule {rule}, alert {alert_id}, count=2; JSON and size checks passed")


if __name__ == "__main__":
    test_oom()
