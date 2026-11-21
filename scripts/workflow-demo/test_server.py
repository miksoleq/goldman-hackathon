import sqlite3
import unittest
from server import respond


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        with self.db:
            self.db.execute("CREATE TABLE workflows (id TEXT PRIMARY KEY, state TEXT, retry_key TEXT)")
            self.db.executemany("INSERT INTO workflows VALUES (?, ?, NULL)", [("w", "failed"), ("done", "completed")])

    def request(self, workflow, key=None):
        with self.db:
            return respond(self.db, workflow, key is not None, key)

    def test_only_failed_workflow_can_be_retried(self):
        self.assertEqual(409, self.request("done", "k")[0])
        self.assertEqual("completed", self.request("done")[1]["status"])
        self.assertEqual(404, self.request("missing", "k")[0])

    def test_retry_is_idempotent_and_visible_on_read(self):
        self.assertEqual("failed", self.request("w")[1]["status"])
        first = self.request("w", "alert-step")
        self.assertEqual("running", first[1]["status"])
        self.assertEqual(first, self.request("w", "alert-step"))
        self.assertEqual(409, self.request("w", "another-step")[0])
        self.assertEqual("running", self.request("w")[1]["status"])
