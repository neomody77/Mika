#!/usr/bin/env python3
"""Opt-in, real MCP/CLI smoke test on a dedicated Android emulator; no model or personal apps.

Install Mika Dev, enable accessibility and run `mika connect` first. This script
opens Settings and Mika's own self-check fixture only. It refuses physical devices.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import mika_cli


class Smoke:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        if (self.output / "report.json").exists():
            raise RuntimeError("Choose a fresh output directory; a report already exists")
        self.report = {"checks": [], "model_configured": False}
        self.session = None
        self.screen = None
        self.client = None

    def adb(self, *args, check=True):
        return subprocess.run([self.args.adb, "-s", self.args.device, *args],
                              capture_output=True, text=True, check=check, timeout=30)

    def cli(self, *args, error=None):
        r = subprocess.run([sys.executable, str(Path(mika_cli.__file__).resolve()), "--device", self.args.device,
                            "--package", self.args.package, "--adb", self.args.adb, *args, "--json"],
                           capture_output=True, text=True, timeout=70)
        data = json.loads(r.stdout)
        if error:
            assert r.returncode != 0 and data.get("error", {}).get("code") == error, data
        else:
            assert r.returncode == 0 and "error" not in data, data
        if data.get("snapshot_id"):
            self.screen = data
        return data

    def rpc(self, method, params=None, auth=True):
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.client.token
        url = self.client.base.removesuffix(mika_cli.API) + "/mcp"
        request = urllib.request.Request(url, json.dumps({"jsonrpc": "2.0", "id": 1, "method": method,
                                                        "params": params or {}}).encode(), headers=headers)
        with self.client.opener.open(request, timeout=40) as response:
            return json.load(response)["result"]

    def mcp(self, name, args=None, error=None):
        data = {"session_id": self.session, **(args or {})} if name != "open" else args or {}
        response = self.rpc("tools/call", {"name": "mika_phone_" + name, "arguments": data})
        result = response.get("structuredContent")
        assert isinstance(result, dict), response
        if error:
            assert response.get("isError") and result.get("error") == error, result
        else:
            assert not response.get("isError") and result.get("ok"), result
        if result.get("snapshot_id"):
            self.screen = result
        return result, response

    def check(self, name, **details):
        self.report["checks"].append({"name": name, "passed": True, **details})
        (self.output / "progress.json").write_text(json.dumps(self.report, indent=2, ensure_ascii=False))
        print("PASS " + name, flush=True)

    def displays(self):
        text = self.adb("shell", "cmd", "display", "get-displays").stdout
        return {int(x) for x in re.findall(r"^Display id (\d+):", text, re.M)}

    def wake_locks(self):
        text = self.adb("shell", "dumpsys", "power").stdout
        active = text.split("Wake Locks:", 1)[1].split("\n\n", 1)[0]
        return [x for x in active.splitlines() if "Mika:task" in x]

    def main_task(self):
        text = self.adb("shell", "dumpsys", "window", "displays").stdout
        display = None
        for line in text.splitlines():
            found = re.search(r"Display: mDisplayId=(\d+)", line)
            if found:
                display = int(found[1])
            if display == 0 and "mFocusedApp=" in line:
                return line.strip()
        raise AssertionError("Cannot identify the emulator's main-screen task")

    def element(self, predicate):
        return next(e for e in self.screen["elements"] if predicate(e))

    def rows(self):
        return [e["name"] for e in self.screen["elements"] if re.fullmatch(r"Row \d+", e["name"])]

    def png(self, data, metadata, name):
        assert data.startswith(b"\x89PNG\r\n\x1a\n")
        size = struct.unpack(">II", data[16:24])
        assert size == (metadata["width"], metadata["height"]), size
        path = self.output / (name + ".png")
        with path.open("xb") as stream:
            stream.write(data)
        return hashlib.sha256(data).hexdigest()

    def released(self, display):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if display not in self.displays() and not self.wake_locks():
                return
            time.sleep(.2)
        raise AssertionError("Display or wake lock was not released")

    def run(self):
        started = time.monotonic()
        assert self.args.device.startswith("emulator-"), "This smoke test only runs on an explicitly selected emulator"
        assert self.adb("shell", "run-as", self.args.package, "id").returncode == 0
        assert self.adb("shell", "run-as", self.args.package, "test", "-e", "files/settings/providers.json", check=False).returncode == 1
        self.report["android"] = self.adb("shell", "getprop", "ro.build.version.release").stdout.strip()
        self.report["sdk"] = int(self.adb("shell", "getprop", "ro.build.version.sdk").stdout)
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(.5)
        main = self.main_task()
        self.check("no_phone_model_configured")

        ns = argparse.Namespace(device=self.args.device, package=self.args.package, adb=self.args.adb,
                                url=None, token_file=None, request_timeout=35, command="phone", progress=False)
        try:
            with mika_cli.connection(ns, []) as (self.client, _):
                info = self.client.discover()
                assert "phone_control" in info["capabilities"]
                init = self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                                "clientInfo": {"name": "emulator-smoke", "version": "1"}})
                assert init["protocolVersion"] == "2025-06-18"
                names = {t["name"] for t in self.rpc("tools/list")["tools"] if t["name"].startswith("mika_phone_")}
                assert len(names) == 10
                self.check("mcp_initialize_and_discovery", tools=sorted(names))
                try:
                    self.rpc("tools/list", auth=False)
                    raise AssertionError("Unauthenticated request was accepted")
                except urllib.error.HTTPError as e:
                    assert e.code == 401
                self.check("unauthenticated_requests_rejected")

                opened = self.cli("phone", "open")
                self.session, display = opened["session_id"], opened["display_id"]
                self.report["size"] = [opened["width"], opened["height"]]
                self.mcp("open", error="session_busy")
                self.check("exclusive_session_and_cli_open")
                apps, _ = self.mcp("list_apps", {"query": "Settings"})
                assert any(a["package_name"] == "com.android.settings" for a in apps["apps"])
                launched = self.cli("phone", "launch", self.session, "com.android.settings")
                assert launched["package_name"] == "com.android.settings" and launched["elements"]
                self.check("background_app_launch_and_read")

                # Fixture setup uses shell's exported test-only activity; all tested reads and input use MCP/CLI.
                component = self.args.package + "/com.wangzan101.mika.selfcheck.SelfCheckTargetActivity"
                self.adb("shell", "am", "start", "--display", str(display), "-n", component)
                time.sleep(.5)
                self.mcp("read_screen")
                assert any(e["name"] == "Mika self-check page" for e in self.screen["elements"]), self.screen
                button = self.element(lambda e: e["name"] == "Tap me" and e["clickable"])
                self.mcp("tap", {"snapshot_id": self.screen["snapshot_id"], "ref": button["ref"]})
                assert any(e["name"] == "Taps: 1" for e in self.screen["elements"])
                button = self.element(lambda e: e["name"] == "Tap me" and e["clickable"])
                self.cli("phone", "tap", self.session, "--snapshot-id", self.screen["snapshot_id"], "--x", str(button["x"]), "--y", str(button["y"]))
                assert any(e["name"] == "Taps: 2" for e in self.screen["elements"])
                self.check("ref_and_coordinate_taps_verified_by_counter")
                field = self.element(lambda e: e["editable"])
                typed = self.cli("phone", "input", self.session, "--snapshot-id", self.screen["snapshot_id"], "--ref", field["ref"], "--text", "Mika模拟器中文123")
                assert typed["verification"] == "match" and any(e["name"] == "Mika模拟器中文123" for e in typed["elements"])
                field = self.element(lambda e: e["editable"])
                typed, _ = self.mcp("input_text", {"snapshot_id": self.screen["snapshot_id"], "ref": field["ref"], "text": "MikaEmulator123"})
                assert typed["verification"] == "match"
                self.check("unicode_and_ascii_input_readback_match")

                before = self.rows()
                scroll = self.element(lambda e: e["scrollable"])
                self.cli("phone", "scroll", self.session, "--snapshot-id", self.screen["snapshot_id"], "--ref", scroll["ref"], "--direction", "down")
                after = self.rows()
                assert before != after and after, (before, after)
                scroll = self.element(lambda e: e["scrollable"])
                self.mcp("scroll", {"snapshot_id": self.screen["snapshot_id"], "ref": scroll["ref"], "direction": "up"})
                assert self.rows() == before, (before, self.rows())
                self.check("bidirectional_scroll_verified_by_visible_rows")

                old = self.screen["snapshot_id"]
                first, reply = self.mcp("screenshot")
                image = next(c for c in reply["content"] if c["type"] == "image")
                assert image["mimeType"] == "image/png"
                first_hash = self.png(base64.b64decode(image["data"]), first, "mcp-frame")
                time.sleep(.7)
                shot = self.cli("phone", "screenshot", self.session, "--output", str(self.output / "cli-frame.png"))
                raw = (self.output / "cli-frame.png").read_bytes()
                assert struct.unpack(">II", raw[16:24]) == (shot["width"], shot["height"])
                assert first_hash != hashlib.sha256(raw).hexdigest(), "Virtual-display frame did not advance"
                self.check("native_mcp_and_cli_png_frames_advance")
                self.mcp("tap", {"snapshot_id": old, "x": 500, "y": 500}, error="stale_snapshot")
                self.check("stale_snapshot_refused")
                assert self.main_task() == main, "Background control changed the main-screen task"
                self.check("main_screen_task_unchanged")
                assert self.wake_locks()
                self.mcp("back")
                self.check("background_back")
                self.mcp("close")
                self.session = None
                self.released(display)
                self.cli("phone", "read", opened["session_id"], error="session_not_found")
                self.check("explicit_close_releases_display_and_wake_lock")

                opened, _ = self.mcp("open")
                self.session = opened["session_id"]
                self.adb("shell", "sh", "/sdcard/Android/data/" + self.args.package + "/files/connect-computer.sh", "off")
                self.released(opened["display_id"])
                old_session, self.session = self.session, None
                self.cli("connect")
                self.cli("phone", "read", old_session, error="session_not_found")
                self.check("listener_stop_revokes_session_and_releases_resources")

            if self.args.check_idle:
                opened = self.cli("phone", "open")
                self.session = opened["session_id"]
                timeout = opened["idle_timeout_s"]
                print("Waiting for real idle expiry (no phone tool polling): %s s" % timeout, flush=True)
                deadline, began = time.monotonic() + timeout + 40, time.monotonic()
                while time.monotonic() < deadline and opened["display_id"] in self.displays():
                    time.sleep(5)
                self.released(opened["display_id"])
                elapsed = round(time.monotonic() - began, 1)
                self.cli("phone", "read", self.session, error="session_not_found")
                self.session = None
                self.check("real_idle_expiry_releases_resources", elapsed_seconds=elapsed)
            self.report["passed"] = True
        except Exception as e:
            self.report.update(passed=False, error=str(e))
            raise
        finally:
            if self.session:
                self.cli("phone", "close", self.session)
            self.report["elapsed_seconds"] = round(time.monotonic() - started, 1)
            (self.output / "report.json").write_text(json.dumps(self.report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--package", default="com.wangzan101.mika.dev")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", required=True)
    parser.add_argument("--check-idle", action="store_true", help="Also wait for the real five-minute idle lease")
    Smoke(parser.parse_args()).run()
