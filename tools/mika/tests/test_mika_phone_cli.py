"""Direct-control CLI transport, file output and uncertain-delivery tests; no model or Android required."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_mika_cli import api, SCRIPT, TOKEN


class PhoneCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.token = self.directory / "token"
        self.token.write_text(TOKEN)

    def invoke(self, server, *args):
        result = subprocess.run([sys.executable, "-I", str(SCRIPT), "--json", "--url",
                                 "http://127.0.0.1:" + str(server.server_port), "--token-file", str(self.token),
                                 "phone", *args], capture_output=True, text=True, timeout=10)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        return result.returncode, json.loads(result.stdout)

    def reply(self, method, path, body):
        if path == "/api/v1":
            return 200, {"api_version": 1, "capabilities": ["tasks", "phone_control"]}, {}
        return 200, {"ok": True, "session_id": "session-1", "snapshot_id": "screen-2"}, {}

    def test_all_direct_commands_use_phone_api_without_submitting_tasks(self):
        commands = [
            (["open"], "open", {}),
            (["apps", "session-1", "--query", "Settings"], "list_apps", {"session_id": "session-1", "query": "Settings"}),
            (["launch", "session-1", "com.android.settings"], "open_app", {"session_id": "session-1", "package_name": "com.android.settings"}),
            (["read", "session-1"], "read_screen", {"session_id": "session-1", "max_elements": 500}),
            (["tap", "session-1", "--snapshot-id", "screen-1", "--ref", "@e1"], "tap",
             {"session_id": "session-1", "snapshot_id": "screen-1", "ref": "@e1"}),
            (["input", "session-1", "--snapshot-id", "screen-1", "--text", "中文 Mika 123"], "input_text",
             {"session_id": "session-1", "snapshot_id": "screen-1", "text": "中文 Mika 123"}),
            (["scroll", "session-1", "--snapshot-id", "screen-1", "--direction", "down"], "scroll",
             {"session_id": "session-1", "snapshot_id": "screen-1", "direction": "down"}),
            (["back", "session-1"], "back", {"session_id": "session-1"}),
            (["close", "session-1"], "close", {"session_id": "session-1"}),
        ]
        with api(self.reply) as server:
            for argv, endpoint, body in commands:
                with self.subTest(command=argv[0]):
                    code, result = self.invoke(server, *argv)
                    self.assertEqual(0, code, result)
                    self.assertEqual(("POST", "/api/v1/phone/" + endpoint, body), server.calls[-1])
            self.assertFalse(any("/tasks" in path or path == "/mcp" for _, path, _ in server.calls))

    def test_screenshot_is_saved_without_emitting_base64_and_never_overwrites(self):
        png = b"\x89PNG\r\n\x1a\n" + b"local-test-frame"
        encoded = base64.b64encode(png).decode()
        def reply(method, path, body):
            if path.endswith("/screenshot"):
                return 200, {"ok": True, "session_id": "session-1", "image": {"mime_type": "image/png", "data": encoded}}, {}
            return self.reply(method, path, body)
        output = self.directory / "frame.png"
        with api(reply) as server:
            code, result = self.invoke(server, "screenshot", "session-1", "--output", str(output))
            self.assertEqual(0, code, result)
            self.assertEqual(png, output.read_bytes())
            self.assertNotIn("image", result)
            self.assertEqual(str(output), result["output"])
            count = len(server.calls)
            code, result = self.invoke(server, "screenshot", "session-1", "--output", str(output))
            self.assertEqual("output_exists", result["error"]["code"])
            self.assertEqual(count, len(server.calls))

    def test_mutations_with_lost_responses_are_not_retried(self):
        def reply(method, path, body):
            return "drop" if path.endswith("/tap") else self.reply(method, path, body)
        with api(reply) as server:
            code, result = self.invoke(server, "tap", "session-1", "--snapshot-id", "screen-1", "--x", "500", "--y", "500")
            self.assertEqual(4, code)
            self.assertEqual("outcome_unknown", result["error"]["code"])
            self.assertEqual(1, sum(path.endswith("/tap") for _, path, _ in server.calls))

    def test_task_only_servers_are_reported_without_falling_back_to_model_tasks(self):
        with api() as server:
            code, result = self.invoke(server, "open")
            self.assertEqual("incompatible_api", result["error"]["code"])
            self.assertEqual(1, len(server.calls))

    def test_phone_errors_and_invalid_images_are_not_successful_outputs(self):
        def reply(method, path, body):
            if path.endswith("/input_text"):
                return 409, {"error": {"code": "stale_snapshot", "message": "Read again"}}, {}
            if path.endswith("/screenshot"):
                return 200, {"ok": True, "session_id": "session-1", "image": {"mime_type": "image/png", "data": "not base64"}}, {}
            return self.reply(method, path, body)
        with api(reply) as server:
            code, result = self.invoke(server, "input", "session-1", "--snapshot-id", "old", "--text", "test")
            self.assertNotEqual(0, code)
            self.assertEqual("stale_snapshot", result["error"]["code"])
            output = self.directory / "invalid.png"
            code, result = self.invoke(server, "screenshot", "session-1", "--output", str(output))
            self.assertNotEqual(0, code)
            self.assertEqual("protocol", result["error"]["code"])
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
