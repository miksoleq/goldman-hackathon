"""Check SMTP and SMS delivery against the disposable Compose providers."""
import http.cookiejar
import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener

control = os.environ["CONTROL_URL"]
engine = os.environ["ENGINE_URL"]
mail = os.environ["MAILPIT_URL"]
sms = os.environ["SMS_MOCK_URL"]
username = os.environ["E2E_USERNAME"]
session = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
provider = build_opener()


def request(url, data=None, method=None, client=session):
    body = None if data is None else json.dumps(data).encode()
    req = Request(url, body, {"Content-Type": "application/json"}, method=method)
    with client.open(req, timeout=5) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def wait(label, probe):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        result = probe()
        if result:
            return result
        time.sleep(0.25)
    raise AssertionError("Timed out: " + label)


def main():
    tag = uuid.uuid4().hex
    title = "Delivery " + tag
    phone = "+12025550123"
    address = tag + "@example.test"
    enabled = {"enabled": True, "minSeverity": "high"}
    request(control + "/api/auth/login", {"username": username, "password": os.environ["E2E_PASSWORD"]})
    for field, invalid in [("sms", "invalid"), ("email", "no-at-sign")]:
        try:
            request(control + "/api/me/notification-contacts/" + field, {"address": invalid}, "PUT")
        except HTTPError as error:
            assert error.code == 400
        else:
            raise AssertionError("Invalid contact accepted for " + field)
    for field, value in [("sms", phone), ("email", address)]:
        request(control + "/api/me/notification-contacts/" + field, {"address": value}, "PUT")
    prefs = control + "/api/me/notification-preferences"
    request(prefs, {"sms": enabled, "email": enabled}, "PUT")
    match = {"all": [
        {"path": "payload.tag", "op": "eq", "value": tag},
        {"any": [
            {"path": "payload.amount", "op": "gt", "value": 1000},
            {"path": "payload.customer.tier", "op": "eq", "value": "vip"}]},
        {"not": {"path": "payload.env", "op": "eq", "value": "test"}}]}
    rule = request(control + "/api/rules", {
        "name": title, "route": {"users": [username]}, "match": match,
        "dedup": {"keys": ["payload.group"], "windowSec": 120}, "severity": "{{payload.level | default: high}}", "title": "Delivery {{payload.tag}}",
        "body": "Diagnostic notification", "runbook": "none"})

    def trigger(group, previous=None):
        request(engine + "/ingest", {"tag": tag, "group": group, "amount": 1500}, client=provider)
        rows = request(control + "/api/monitor/alerts")
        return next((r["alertId"] for r in rows if r["ruleId"] == rule["id"] and r["alertId"] != previous), None)

    def deliveries(alert):
        rows = request(control + "/api/me/notification-deliveries?alertId=" + alert)
        return {r["channel"]: r for r in rows}

    request(sms + "/availability", {"available": False}, client=provider)
    try:
        alert = wait("first matching alert", lambda: trigger("one"))
        wait("SMS retry after 503", lambda: deliveries(alert).get("sms", {}).get("status") == "retrying")
        wait("SMTP delivery during SMS outage", lambda: deliveries(alert).get("email", {}).get("status") == "sent")
        letters = request(mail + "/api/v1/messages", client=provider)["messages"]
        matches = [m for m in letters if m["Subject"] == title and m["To"][0]["Address"] == address]
        assert len(matches) == 1, matches
        letter = request(mail + "/api/v1/message/" + matches[0]["ID"], client=provider)
        assert alert in letter["Text"] and "Diagnostic notification" in letter["Text"]
    finally:
        request(sms + "/availability", {"available": True}, client=provider)

    wait("successful retry", lambda: deliveries(alert)["sms"]["status"] == "sent")
    delivery = deliveries(alert)["sms"]
    messages = request(sms + "/messages", client=provider)
    matches = [m for m in messages if m["notificationId"] == delivery["notificationId"]]
    assert len(matches) == 1 and delivery["attempts"] >= 2, (matches, delivery)
    assert matches[0]["to"] == phone and title in matches[0]["text"] and alert in matches[0]["text"]
    request(prefs, {"sms": dict(enabled, enabled=False)}, "PUT")
    second = wait("second matching alert", lambda: trigger("two", alert))
    wait("email after SMS opt-out", lambda: deliveries(second).get("email", {}).get("status") == "sent")
    assert "sms" not in deliveries(second)
    print("PASS: matched rule, SMTP recipient and body, SMS retry, provider idempotency key, SMS opt-out")


if __name__ == "__main__":
    main()
