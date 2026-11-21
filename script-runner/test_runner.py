"""Run with docker compose exec -T script-runner python -B - < script-runner/test_runner.py."""
import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from main import LOG_LIMIT, log_text, validate_result


class ResultTests(unittest.TestCase):
    def test_logs_have_a_utf8_byte_limit_and_can_be_stored_in_jsonb(self):
        text = log_text(b"\xff\x00" + ("🙂" * 5000).encode())
        self.assertTrue(text.startswith("��"))
        self.assertLessEqual(len(text.encode()), LOG_LIMIT)

    def test_invalid_jsonb_strings_and_nonfinite_numbers_are_rejected(self):
        for item in ("\0", "\ud800", float("nan")):
            with self.subTest(item=repr(item)), self.assertRaises(ValueError):
                validate_result({"item": [item]})


class ExecuteHttpTests(unittest.TestCase):
    def execute(self, code, **fields):
        context = {"previousStepResult": {"status": "UP"}, "event": {}, "alert": {}}
        payload = json.dumps({"code": code, "context": context, **fields}).encode()
        request = Request("http://localhost:8001/execute", payload, {"Content-Type": "application/json"})
        with urlopen(request, timeout=8) as response:
            return json.load(response)

    def test_chains_json_and_separates_stdout_stderr_from_the_result(self):
        reply = self.execute("""import sys
def run(ctx):
    print("checking")
    print("diagnostic", file=sys.stderr)
    return {"healthy": ctx["previousStepResult"]["status"] == "UP"}
""")
        self.assertEqual("SUCCESS", reply["status"])
        self.assertEqual(0, reply["exitCode"])
        self.assertEqual({"healthy": True}, reply["result"])
        self.assertEqual(("checking\n", "diagnostic\n"), (reply["stdout"], reply["stderr"]))
        self.assertGreater(reply["durationMs"], 0)

    def test_infinite_loop_is_killed_and_next_execution_succeeds(self):
        result = self.execute("print('starting')\nwhile True: pass", timeoutMs=400)
        self.assertEqual("TIMEOUT", result["status"])
        self.assertLess(result["exitCode"], 0)
        self.assertLess(result["durationMs"], 1500)
        self.assertEqual("starting\n", result["stdout"])
        self.assertEqual("SUCCESS", self.execute("pass")["status"])

    def test_timeout_is_capped_at_five_seconds(self):
        reply = self.execute("import time\ntime.sleep(10)", timeoutMs=20000)
        self.assertEqual("TIMEOUT", reply["status"])
        self.assertLess(reply["durationMs"], 6000)

    def test_runtime_syntax_and_serialization_errors(self):
        for code, message in (("raise ValueError('oops')", "ValueError: oops"), ("def run(:", "SyntaxError"),
                              ("def run(ctx): return {1, 2}", "TypeError")):
            with self.subTest(code=code):
                reply = self.execute(code)
                self.assertEqual("ERROR", reply["status"])
                self.assertIn(message, reply["stderr"])
                self.assertIsNone(reply["result"])
        with urlopen("http://localhost:8001/health", timeout=2) as response:
            self.assertEqual({"status": "UP"}, json.load(response))

    def test_large_logs_are_drained_after_truncation(self):
        reply = self.execute("import sys\nprint('a'*100000)\nprint('b'*100000,file=sys.stderr)")
        self.assertEqual("SUCCESS", reply["status"])
        self.assertEqual("a" * LOG_LIMIT, reply["stdout"])
        self.assertEqual("b" * LOG_LIMIT, reply["stderr"])

    def test_empty_environment_isolated_interpreter_and_resource_limits(self):
        flags = self.execute("import os, sys\ndef run(ctx): return [dict(os.environ), sys.flags.isolated]")
        self.assertEqual([{}, 1], flags["result"])
        reply = self.execute("bytearray(160 * 1024 * 1024)")
        self.assertEqual("ERROR", reply["status"])
        self.assertIn("MemoryError", reply["stderr"])

    def test_script_cannot_open_files_or_create_sockets(self):
        for code in ("open('/etc/hostname')", "import socket\nsocket.socket()"):
            with self.subTest(code=code):
                self.assertEqual("ERROR", self.execute(code)["status"])

    def test_invalid_requests_are_rejected(self):
        with self.assertRaises(HTTPError) as error:
            self.execute("pass", timeoutMs=0)
        self.assertEqual(422, error.exception.code)


if __name__ == "__main__":
    unittest.main()
