"""Local SMS simulator. Only accepted deliveries are recorded."""
import json
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATABASE = os.environ.get("SMS_DATABASE", "/data/messages.sqlite3")


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/messages":
            return self.reply(404, {})
        with sqlite3.connect(DATABASE) as db:
            items = db.execute("SELECT id, recipient, message FROM messages ORDER BY rowid DESC LIMIT 100")
            self.reply(200, [dict(notificationId=k, to=r, text=m) for k, r, m in items])

    def do_POST(self):
        if self.path not in ("/messages", "/availability"):
            return self.reply(404, {})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 16384:
                return self.reply(400, {})
            body = json.loads(self.rfile.read(size))
            with sqlite3.connect(DATABASE) as db:
                if self.path == "/availability":
                    available = body["available"]
                    if type(available) is not bool:
                        return self.reply(400, {})
                    db.execute("UPDATE gateway SET available=?", (available,))
                else:
                    key = self.headers.get("Idempotency-Key")
                    if not key or not all(isinstance(body[k], str) and body[k] for k in ("to", "text")):
                        return self.reply(400, {})
                    if not db.execute("SELECT available FROM gateway").fetchone()[0]:
                        return self.reply(503, {})
                    db.execute("INSERT OR IGNORE INTO messages VALUES (?, ?, ?)", (key, body["to"], body["text"]))
            self.reply(200, {})
        except (KeyError, TypeError, ValueError):
            self.reply(400, {})


if __name__ == "__main__":
    with sqlite3.connect(DATABASE) as db:
        db.execute("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, recipient TEXT, message TEXT)")
        db.execute("CREATE TABLE IF NOT EXISTS gateway (id INTEGER PRIMARY KEY, available INTEGER)")
        db.execute("INSERT OR IGNORE INTO gateway VALUES (1, 1)")
    ThreadingHTTPServer(("", int(os.environ.get("SMS_PORT", "8080"))), Handler).serve_forever()
