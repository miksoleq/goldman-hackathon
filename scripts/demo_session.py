"""Authenticate smoke-test writes against the supplied local demo Control service."""
import json
from urllib.request import Request, urlopen


def login_cookie(control):
    credentials = json.dumps({"username": "demo.member", "password": "Runbook123!"}).encode()
    request = Request(control + "/api/auth/login", data=credentials,
                      headers={"Content-Type": "application/json"})
    with urlopen(request, timeout=5) as response:
        return response.headers["Set-Cookie"].split(";", 1)[0]
