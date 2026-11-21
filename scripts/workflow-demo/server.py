"""Internal workflow provider for the runbook demo. Seed workflows with the CLI."""
import json
import os
import re
import sqlite3
import sys
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

DATABASE = os.getenv("WORKFLOW_DATABASE", "/data/workflows.sqlite")


def respond(db, workflow_id, retry, key):
    if retry:
        db.execute("BEGIN IMMEDIATE")
    row = db.execute("SELECT state, retry_key FROM workflows WHERE id = ?", (workflow_id,)).fetchone()
    if row is None:
        return 404, {"message": "Unknown workflow"}
    state, prior_key = row
    if retry:
        if not key:
            return 400, {"message": "Idempotency key required"}
        if key != prior_key:
            if state != "failed":
                return 409, {"message": "Only failed workflows can be retried", "status": state}
            state = "running"
            db.execute("UPDATE workflows SET state = ?, retry_key = ? WHERE id = ?", (state, key, workflow_id))
    return 200, {"workflowId": workflow_id, "status": state}


class Handler(BaseHTTPRequestHandler):
    def handle_workflow(self, retry):
        path = r"/workflows/([^/]+)/retry" if retry else r"/workflows/([^/]+)"
        match = re.fullmatch(path, self.path)
        if match is None:
            self.send_error(404)
            return
        with closing(sqlite3.connect(DATABASE)) as db, db:
            code, result = respond(db, unquote(match[1]), retry, self.headers.get("Idempotency-Key"))
        body = json.dumps(result).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.handle_workflow(False)

    def do_POST(self):
        self.handle_workflow(True)


if __name__ == "__main__":
    with closing(sqlite3.connect(DATABASE)) as db, db:
        db.execute("CREATE TABLE IF NOT EXISTS workflows (id TEXT PRIMARY KEY, state TEXT NOT NULL, retry_key TEXT)")
        if len(sys.argv) == 3 and sys.argv[1] == "seed":
            db.execute("INSERT OR IGNORE INTO workflows(id, state) VALUES (?, 'failed')", (sys.argv[2],))
    if len(sys.argv) == 3 and sys.argv[1] == "seed":
        sys.exit(0)
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
