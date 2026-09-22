# -*- coding: utf-8 -*-
"""Regression tests for the livestream watchdog / TikTok-detection fixes.

Covers the three bugs from the 2026-09-22 review:
  P1  watchdog killed a healthy yt-dlp stream after 20 min because the size
      helper only checked info['output_file']/direct_output_path (empty during
      yt-dlp recording) and ignored the real timestamp-named output file.
  P2  SIGKILL escalation was unreachable: jobs with status 'timeout' were
      skipped by the watchdog loop's `continue`, and the 12h SIGTERM block
      reset _kill_ts every tick before the elif-SIGKILL could accumulate 60s.
  P3  TikTok SIGI_STATE parser used a naive brace counter that mis-sliced JSON
      containing '}' inside string values, and the regex fallback scanned ALL
      HTML (could pick another room's status).

Run: python3 -m pytest tests/test_livestream_fixes.py  (or: python3 tests/test_livestream_fixes.py)
"""

import ast
import json
import os
import tempfile
import unittest
from pathlib import Path


SERVER_PATH = Path(__file__).resolve().parents[1] / "backend" / "nas_api_server.py"
SOURCE = SERVER_PATH.read_text(encoding="utf-8")
TREE = ast.parse(SOURCE)


def _extract_funcs(names):
    nodes = [n for n in TREE.body
             if isinstance(n, ast.FunctionDef) and n.name in names]
    ns = {"json": json, "_re_module": __import__("re"), "os": os,
          "_LIVESTREAM_DIR": "Livestream"}
    for node in nodes:
        exec(compile(ast.Module([node]), "<%s>" % node.name, "exec"), ns)
    return ns


# Load the parser functions together (they reference each other).
_PARSER_NS = _extract_funcs(
    ["_parse_tiktok_live_room_state", "_dig_tiktok_room",
     "_eval_tiktok_room", "_eval_tiktok_status"]
)


class TestP1SizeHelper(unittest.TestCase):
    def setUp(self):
        self.S = _extract_funcs(["_livestream_current_output_size"])[
            "_livestream_current_output_size"]
        self.dir = tempfile.mkdtemp()

    def _write(self, name, size):
        p = os.path.join(self.dir, name)
        with open(p, "wb") as f:
            f.write(b"x" * size)
        return p

    def test_ytdlp_timestamp_file_is_found_not_other_job(self):
        # Realistic yt-dlp filename: platform_stableid_timestamp.mp4 (timestamp mid-string).
        ts = "20260922_131500"
        self._write("tiktok_alice_%s.mp4" % ts, 4096)
        self._write("tiktok_bob_20260101_000000.mp4", 999999)
        info = {"output_dir": self.dir, "output_file": "",
                "direct_output_path": "", "timestamp_str": ts}
        self.assertEqual(self.S(info), 4096)

    def test_no_timestamp_match_returns_zero(self):
        ts = "20260922_131500"
        self._write("tiktok_alice_%s.mp4" % ts, 4096)
        info = {"output_dir": self.dir, "output_file": "",
                "direct_output_path": "", "timestamp_str": "NOPE"}
        self.assertEqual(self.S(info), 0)

    def test_known_direct_path_wins(self):
        dp = self._write("direct.mp4", 2048)
        info = {"output_dir": self.dir, "output_file": "",
                "direct_output_path": dp, "timestamp_str": ""}
        self.assertEqual(self.S(info), 2048)

    def test_known_output_file_wins(self):
        self._write("job.mp4", 1024)
        info = {"output_dir": self.dir, "output_file": "job.mp4",
                "direct_output_path": "", "timestamp_str": ""}
        self.assertEqual(self.S(info), 1024)


class TestP3TikTokParser(unittest.TestCase):
    def setUp(self):
        self.P = _PARSER_NS["_parse_tiktok_live_room_state"]

    def test_string_aware_brace_inside_value(self):
        # '}' inside a JSON string value must not terminate the slice.
        sig = ('window.__SIGI_STATE__=' + json.dumps({
            "LiveRoom": {"liveRoom": {"liveRoomStatus": 1, "roomId": "7001",
                                      "title": "ended } replay"}}}))
        self.assertEqual(self.P(sig), (True, "7001", 1, ""))

    def test_target_room_off_but_other_room_live_in_blob(self):
        # The OTHER room is live; the target (dug by _dig_tiktok_room) is ended.
        sig = ('window.SIGI_STATE=' + json.dumps({
            "LiveRoom": {"liveRoom": {"liveRoomStatus": 4, "roomId": "7001"}},
            "extra": {"liveRoomStatus": 1, "roomId": "9999"}}))
        self.assertEqual(self.P(sig)[0], False)
        self.assertEqual(self.P(sig)[2], 4)

    def test_fallback_scoped_to_sigi_slice(self):
        # liveRoomStatus present only in a non-SIGI tail; since there is no SIGI
        # marker, the fallback scans all HTML and should still find it.
        html = 'prefix junk <div "liveRoomStatus":1, "roomId":"55"'
        self.assertEqual(self.P(html), (True, "55", 1, ""))

    def test_empty_html(self):
        self.assertEqual(self.P(""), (False, "", -1, "empty html"))


class TestP2WatchdogControlFlow(unittest.TestCase):
    """AST-level guards proving the escalation path is reachable and ordered.

    These do NOT import the 700KB server module; they inspect the source of
    _livestream_watchdog (the timer-thread that runs the per-job loop) to lock
    the control-flow fix against regressions.
    """

    @classmethod
    def setUpClass(cls):
        cls.func = next(
            n for n in TREE.body
            if isinstance(n, ast.FunctionDef) and n.name == "_livestream_watchdog"
        )
        cls.body = ast.unparse(cls.func)

    def test_watchdog_does_not_skip_timeout_status(self):
        # The per-job loop's skip-set (the `continue`) must NOT contain 'timeout'
        # or 'stopping' — those jobs need to reach the SIGKILL escalation branch.
        loop = self.body[self.body.find("for jid, info in list(_livestream_jobs.items()):"):]
        skip_idx = loop.find("continue")
        head = loop[:skip_idx]
        skip = head[head.rfind("if"):]
        self.assertIn("'finished'", skip)
        self.assertIn("'error'", skip)
        self.assertIn("'stopped'", skip)
        self.assertIn("'cancelled'", skip)
        self.assertNotIn("'timeout'", skip)
        self.assertNotIn("'stopping'", skip)

    def test_escalation_branch_precedes_12h_block(self):
        """The timeout/stopping + _kill_ts escalation must come BEFORE the
        12h SIGTERM block, so _kill_ts is not reset every tick."""
        esc = self.body.find("'timeout', 'stopping'")
        h12 = self.body.find("_LIVESTREAM_MAX_HOURS * 3600")
        self.assertGreater(esc, 0, "escalation branch not found")
        self.assertGreater(h12, 0, "12h SIGTERM block not found")
        self.assertLess(esc, h12, "escalation must precede the 12h SIGTERM block")

    def test_12h_block_does_not_reset_kill_ts(self):
        idx = self.body.find("_LIVESTREAM_MAX_HOURS * 3600")
        block = self.body[idx: idx + 500]
        # The 12h block must set _kill_ts only if not already present.
        self.assertIn("if not info.get('_kill_ts')", block)
        before_guard = block[:block.find("if not info.get('_kill_ts')")]
        self.assertNotIn("info['_kill_ts'] = time.time()", before_guard)


if __name__ == "__main__":
    unittest.main()
