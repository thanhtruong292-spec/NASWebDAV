# -*- coding: utf-8 -*-
"""
Tests cho Social Extractor logic trong nas_api_server.py
Chay: python3 -m unittest tests.test_social_extractor -v

Phase: TDD — Phase 5 (RED for fixes)
Symptom: code review found H1-H3 (create_job no validate, url parse bypass @, get_job race)
Completion criterion: Test RED cho cac fixes, sau GREEN khi implement
"""

import unittest
import ipaddress
import socket
import time
from unittest.mock import patch

try:
    from tests.social_logic_loader import load_social_logic
except ImportError:
    from social_logic_loader import load_social_logic

_social_logic = load_social_logic()
_social_validate_url = _social_logic._social_validate_url
_social_sanitize_folder = _social_logic._social_sanitize_folder
_social_create_job = _social_logic._social_create_job
_social_get_job = _social_logic._social_get_job
_social_download_jobs = _social_logic._social_download_jobs
_social_remove_expired_jobs = _social_logic._social_remove_expired_jobs
_detect_platform = _social_logic._detect_platform
SOCIAL_MAX_CONCURRENT = _social_logic.SOCIAL_MAX_CONCURRENT

_AUTO_FINISHED_AT = object()


def _make_job(job_id, status="queued", age_seconds=0, finished_at=_AUTO_FINISHED_AT, **changes):
    started_at = time.time() - age_seconds
    if finished_at is _AUTO_FINISHED_AT:
        finished_at = started_at if status in ("completed", "error") else None
    job = {
        "job_id": job_id,
        "url": "https://www.youtube.com/watch?v=%s" % job_id,
        "folder": "Downloads/social",
        "status": status,
        "progress": 100 if status == "completed" else 0,
        "filename": None,
        "size": 0,
        "platform": "youtube",
        "error_reason": None,
        "started_at": started_at,
        "finished_at": finished_at,
    }
    job.update(changes)
    return job


_dns_patcher = None


def _deterministic_getaddrinfo(host, port, family=0, socktype=0):
    try:
        address = str(ipaddress.ip_address(host))
    except ValueError:
        address = "93.184.216.34"
    resolved_family = socket.AF_INET6 if ":" in address else socket.AF_INET
    sockaddr = (address, port, 0, 0) if resolved_family == socket.AF_INET6 else (address, port)
    return [(resolved_family, socket.SOCK_STREAM, 6, "", sockaddr)]


def setUpModule():
    global _dns_patcher
    _dns_patcher = patch("socket.getaddrinfo", side_effect=_deterministic_getaddrinfo)
    _dns_patcher.start()


def tearDownModule():
    if _dns_patcher is not None:
        _dns_patcher.stop()


class TestValidateUrl(unittest.TestCase):
    """H1: reject private IPs | H2: reject dangerous schemes | H3: accept public"""

    def test_reject_localhost(self):
        self.assertFalse(_social_validate_url("http://127.0.0.1/video.mp4"))

    def test_reject_192_168(self):
        self.assertFalse(_social_validate_url("http://192.168.1.1/video.mp4"))

    def test_reject_10_x(self):
        self.assertFalse(_social_validate_url("http://10.0.0.1/video.mp4"))

    def test_reject_172_16(self):
        self.assertFalse(_social_validate_url("http://172.16.0.1/video.mp4"))

    def test_reject_javascript_scheme(self):
        self.assertFalse(_social_validate_url("javascript:alert(1)"))

    def test_reject_data_scheme(self):
        self.assertFalse(_social_validate_url("data:text/html,<script>"))

    def test_reject_empty_url(self):
        self.assertFalse(_social_validate_url(""))

    def test_reject_ftp_scheme(self):
        self.assertFalse(_social_validate_url("ftp://example.com/file.mp4"))

    def test_accept_youtube(self):
        self.assertTrue(_social_validate_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

    def test_accept_tiktok(self):
        self.assertTrue(_social_validate_url("https://www.tiktok.com/@user/video/123456"))

    def test_accept_instagram(self):
        self.assertTrue(_social_validate_url("https://www.instagram.com/reel/ABC123/"))

    def test_reject_hostname_that_resolves_to_private_ip(self):
        private_result = [
            (2, 1, 6, "", ("192.168.1.25", 443)),
        ]
        with patch("socket.getaddrinfo", return_value=private_result):
            self.assertFalse(_social_validate_url("https://video.example.test/watch/1"))

    def test_reject_unsupported_public_domain(self):
        self.assertFalse(_social_validate_url("https://downloads.example.com/video.mp4"))

    def test_reject_youtube_open_redirect_path(self):
        self.assertFalse(
            _social_validate_url(
                "https://www.youtube.com/redirect?q=http://127.0.0.1/private"
            )
        )

    # ── Facebook URLs ───────────────────────────────────────────────────────────
    def test_accept_facebook_video(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/user/videos/123456789/"))

    def test_accept_facebook_video_short(self):
        self.assertTrue(_social_validate_url("https://fb.watch/abc123xyz/"))

    def test_accept_facebook_story(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/stories/123456789/"))

    def test_accept_facebook_photo_video(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/photo.php?v=123456"))

    def test_accept_facebook_reel(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/reel/123456789/"))

    def test_accept_facebook_watch(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/watch/?v=123456789"))

    def test_accept_facebook_watch_no_trailing_slash(self):
        # Client (Android SocialShareParser) accepts both /watch and /watch/;
        # backend must match so a shared facebook.com/watch?v=... URL that passes
        # the client is not silently rejected by the server.
        self.assertTrue(_social_validate_url("https://www.facebook.com/watch?v=123456789"))

    def test_accept_facebook_shared_reel(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/share/r/AbCdEf123/"))

    def test_accept_facebook_shared_video(self):
        self.assertTrue(_social_validate_url("https://www.facebook.com/share/v/AbCdEf123/"))


class TestSanitizeFolder(unittest.TestCase):
    """H4: reject path traversal | H5: reject absolute | H6: accept safe"""

    def test_reject_dotdot(self):
        self.assertEqual(_social_sanitize_folder("../../etc/passwd"), "Downloads/social/")

    def test_reject_dotdot_middle(self):
        self.assertEqual(_social_sanitize_folder("folder/../../etc"), "Downloads/social/")

    def test_reject_absolute_unix(self):
        self.assertEqual(_social_sanitize_folder("/etc/passwd"), "Downloads/social/")

    def test_reject_absolute_windows(self):
        self.assertEqual(_social_sanitize_folder("C:\\Windows\\System32"), "Downloads/social/")

    def test_reject_rooted_backslash_path(self):
        self.assertEqual(_social_sanitize_folder("\\etc\\passwd"), "Downloads/social/")

    def test_accept_simple_folder(self):
        self.assertEqual(_social_sanitize_folder("Downloads/social/"), "Downloads/social")

    def test_accept_nested_folder(self):
        self.assertEqual(_social_sanitize_folder("My/Videos"), "My/Videos")

    def test_reject_folder_deeper_than_two_levels(self):
        self.assertEqual(_social_sanitize_folder("one/two/three"), "Downloads/social/")

    def test_default_folder(self):
        self.assertEqual(_social_sanitize_folder(""), "Downloads/social/")

    def test_none_folder(self):
        self.assertEqual(_social_sanitize_folder(None), "Downloads/social/")


class TestConcurrencyLimit(unittest.TestCase):
    """H7: max concurrent enforced | H8: job counter starts at 0"""

    def test_max_concurrent_is_two(self):
        self.assertEqual(SOCIAL_MAX_CONCURRENT, 2)

    def test_active_jobs_counter_starts_zero(self):
        self.assertEqual(len(_social_download_jobs), 0)


# ═══════════════════════════════════════════════════════════════
# TESTS CHO CODE REVIEW FIXES (Phase 5 RED)
# ═══════════════════════════════════════════════════════════════


class TestCreateJobValidatesUrl(unittest.TestCase):
    """Fix H1: _social_create_job phai validate URL truoc khi tao job"""

    def setUp(self):
        # Clear state truoc moi test
        _social_download_jobs.clear()

    def test_reject_private_ip_in_create_job(self):
        job_id, err = _social_create_job("http://192.168.1.1/video.mp4", "Downloads/social/")
        self.assertIsNone(job_id)
        self.assertIsNotNone(err)
        self.assertIn("URL", err)

    def test_reject_localhost_in_create_job(self):
        job_id, err = _social_create_job("http://127.0.0.1/video.mp4", "Downloads/social/")
        self.assertIsNone(job_id)
        self.assertIsNotNone(err)

    def test_reject_javascript_in_create_job(self):
        job_id, err = _social_create_job("javascript:alert(1)", "Downloads/social/")
        self.assertIsNone(job_id)
        self.assertIsNotNone(err)

    def test_reject_empty_url_in_create_job(self):
        job_id, err = _social_create_job("", "Downloads/social/")
        self.assertIsNone(job_id)
        self.assertIsNotNone(err)

    def test_accept_valid_url_in_create_job(self):
        job_id, err = _social_create_job("https://www.youtube.com/watch?v=abc", "Downloads/social")
        self.assertIsNotNone(job_id)
        self.assertIsNone(err)
        # Cleanup
        if job_id and job_id in _social_download_jobs:
            del _social_download_jobs[job_id]


class TestUrlParsingRobustness(unittest.TestCase):
    """Fix H3: URL parsing phai dung urllib.parse, chong bypass bang @"""

    def test_reject_url_with_at_in_path(self):
        # http://youtube.com@127.0.0.1 — attacker try bypass
        self.assertFalse(_social_validate_url("http://youtube.com@127.0.0.1/video"))

    def test_reject_url_with_multiple_at(self):
        self.assertFalse(_social_validate_url("http://a@b@127.0.0.1/video"))

    def test_reject_malformed_scheme(self):
        self.assertFalse(_social_validate_url("http:/\\/evil.com"))

    def test_reject_url_without_scheme(self):
        self.assertFalse(_social_validate_url("www.youtube.com/watch"))

    def test_reject_url_with_unicode_bypass(self):
        # U+200B zero-width space inside an otherwise allowed hostname.
        self.assertFalse(
            _social_validate_url("https://www.youtube.com\u200b/watch?v=abc")
        )


class TestDestinationSafety(unittest.TestCase):
    def test_destination_filename_is_unique_per_job(self):
        allocator = getattr(_social_logic, "_social_destination_path", None)
        self.assertIsNotNone(allocator, "missing collision-proof destination allocator")

        first = allocator("/srv/webdav/Downloads", "video.mp4", "job-a")
        second = allocator("/srv/webdav/Downloads", "video.mp4", "job-b")

        self.assertNotEqual(first, second)
        self.assertTrue(first.endswith("video_job-a.mp4"))
        self.assertTrue(second.endswith("video_job-b.mp4"))

    def test_destination_folder_rejects_symlink_escape(self):
        resolver = getattr(_social_logic, "_social_destination_dir", None)
        self.assertIsNotNone(resolver, "missing realpath containment guard")

        original_realpath = _social_logic.os.path.realpath

        def fake_realpath(path):
            normalized = str(path).replace("\\", "/")
            if normalized.rstrip("/") == "/srv/webdav":
                return "/srv/webdav"
            if normalized.startswith("/srv/webdav/linked"):
                return "/etc/escaped"
            return original_realpath(path)

        with patch.object(_social_logic, "WEBDAV_FILE_ROOT", "/srv/webdav", create=True), patch(
            "os.path.realpath", side_effect=fake_realpath
        ):
            self.assertIsNone(resolver("linked/output"))


class TestGetJobReturnsDeepCopy(unittest.TestCase):
    """Fix H2: _social_get_job phai tra deep copy de caller khong mutate state"""

    def setUp(self):
        _social_download_jobs.clear()

    def test_get_job_returns_independent_dict(self):
        job_id, _ = _social_create_job("https://www.youtube.com/watch?v=abc", "Downloads/social")
        try:
            job1 = _social_get_job(job_id)
            self.assertIsNotNone(job1)
            # Mutate returned dict
            job1["status"] = "mutated_by_caller"
            # Lay lai phai thay gia tri goc khong doi
            job2 = _social_get_job(job_id)
            self.assertNotEqual(job2.get("status"), "mutated_by_caller")
        finally:
            if job_id in _social_download_jobs:
                del _social_download_jobs[job_id]


class TestCreateJobEnforcesLimit(unittest.TestCase):
    """M1 fix: test concurrency limit thuc su duoc enforce"""

    def setUp(self):
        _social_download_jobs.clear()

    def test_reject_when_at_max(self):
        # Fill up den max
        for i in range(SOCIAL_MAX_CONCURRENT):
            jid, err = _social_create_job(
                "https://www.youtube.com/watch?v=job%d" % i,
                "Downloads/social"
            )
            self.assertIsNotNone(jid, "Job %d should be accepted" % i)
            self.assertIsNone(err)

        # Job thu 3 phai bi reject
        jid3, err3 = _social_create_job(
            "https://www.youtube.com/watch?v=overflow",
            "Downloads/social"
        )
        self.assertIsNone(jid3)
        self.assertIsNotNone(err3)
        self.assertIn("Max concurrent", err3)

    def test_completed_jobs_dont_count(self):
        # Fill 2 jobs, complete 1, them 1 moi phai duoc accept
        for i in range(SOCIAL_MAX_CONCURRENT):
            jid, _ = _social_create_job(
                "https://www.youtube.com/watch?v=job%d" % i,
                "Downloads/social"
            )
            _social_download_jobs[jid]["status"] = "completed"

        # Job moi phai accepted (vi 2 jobs cu da completed)
        new_jid, err = _social_create_job(
            "https://www.youtube.com/watch?v=newone",
            "Downloads/social"
        )
        self.assertIsNotNone(new_jid)
        self.assertIsNone(err)

    def test_stale_active_jobs_do_not_consume_concurrency_slots(self):
        import time
        stale_time = time.time() - 7200
        for i in range(SOCIAL_MAX_CONCURRENT):
            jid = "stale_%d" % i
            _social_download_jobs[jid] = {
                "job_id": jid,
                "url": "https://www.youtube.com/watch?v=stale%d" % i,
                "folder": "Downloads/social",
                "status": "queued",
                "progress": 0,
                "filename": None,
                "size": 0,
                "platform": "youtube",
                "error_reason": None,
                "started_at": stale_time,
                "finished_at": None,
            }

        new_jid, err = _social_create_job(
            "https://www.youtube.com/watch?v=fresh",
            "Downloads/social",
        )
        self.assertIsNotNone(new_jid)
        self.assertIsNone(err)

    def tearDown(self):
        _social_download_jobs.clear()


class TestRemoveExpiredJobs(unittest.TestCase):
    """Test one-hour TTL cleanup for every job state."""

    def setUp(self):
        _social_download_jobs.clear()

    def tearDown(self):
        _social_download_jobs.clear()

    def test_removes_old_completed_jobs(self):
        jid = "cleanup_test1"
        _social_download_jobs[jid] = _make_job(
            jid, "completed", 7200, filename="abc.mp4", size=1024
        )
        removed = _social_remove_expired_jobs(max_age_sec=3600)  # 1h threshold
        self.assertEqual(removed, 1)
        self.assertNotIn(jid, _social_download_jobs)

    def test_removes_old_error_jobs(self):
        jid = "cleanup_test2"
        _social_download_jobs[jid] = _make_job(
            jid, "error", 7200, progress=45, error_reason="yt-dlp failed"
        )
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 1)
        self.assertNotIn(jid, _social_download_jobs)

    def test_preserves_active_queued_jobs(self):
        jid = "cleanup_test3"
        _social_download_jobs[jid] = _make_job(jid, "queued", 60)
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 0)
        self.assertIn(jid, _social_download_jobs)

    def test_removes_queued_job_older_than_ttl(self):
        jid = "stale_queued"
        _social_download_jobs[jid] = _make_job(jid, "queued", 7200)
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 1)
        self.assertNotIn(jid, _social_download_jobs)

    def test_preserves_active_downloading_jobs(self):
        jid = "cleanup_test4"
        _social_download_jobs[jid] = _make_job(jid, "downloading", 60, progress=50)
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 0)
        self.assertIn(jid, _social_download_jobs)

    def test_preserves_recent_completed_jobs(self):
        jid = "cleanup_test5"
        _social_download_jobs[jid] = _make_job(
            jid, "completed", 60, filename="recent.mp4", size=2048
        )
        removed = _social_remove_expired_jobs(max_age_sec=3600)  # 1h threshold
        self.assertEqual(removed, 0)
        self.assertIn(jid, _social_download_jobs)

    def test_removes_multiple_old_jobs(self):
        for i in range(3):
            jid = "multi_%d" % i
            _social_download_jobs[jid] = _make_job(
                jid, "completed", 7200, filename="multi%d.mp4" % i, size=512
            )
        # Add 1 active job (should NOT be removed)
        _social_download_jobs["active_keep"] = _make_job(
            "active_keep", "downloading", 60, progress=30
        )
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 3)
        self.assertNotIn("multi_0", _social_download_jobs)
        self.assertNotIn("multi_1", _social_download_jobs)
        self.assertNotIn("multi_2", _social_download_jobs)
        self.assertIn("active_keep", _social_download_jobs)

    def test_falls_back_to_started_at_if_finished_at_none(self):
        jid = "fallback_test"
        _social_download_jobs[jid] = _make_job(
            jid, "completed", 7200, finished_at=None, filename="fallback.mp4", size=1024
        )
        removed = _social_remove_expired_jobs(max_age_sec=3600)
        self.assertEqual(removed, 1)
        self.assertNotIn(jid, _social_download_jobs)


# ═══════════════════════════════════════════════════════════════
# TESTS CHO PLATFORM DETECTION
# ═══════════════════════════════════════════════════════════════


class TestDetectPlatform(unittest.TestCase):
    """Test _detect_platform cho cac dinh dang URL khac nhau."""

    def test_facebook_video(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/user/videos/123456789/"), "facebook")

    def test_facebook_fbwatch(self):
        self.assertEqual(_detect_platform("https://fb.watch/abc123xyz/"), "facebook")

    def test_facebook_story(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/stories/123456789/"), "facebook")

    def test_facebook_reel(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/reel/123456789/"), "facebook")

    def test_facebook_watch(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/watch/?v=123456789"), "facebook")

    def test_facebook_photo(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/photo.php?v=123456"), "facebook")

    def test_facebook_story_s(self):
        self.assertEqual(_detect_platform("https://www.facebook.com/s/123456789"), "facebook")

    def test_tiktok(self):
        self.assertEqual(_detect_platform("https://www.tiktok.com/@user/video/123456"), "tiktok")

    def test_youtube(self):
        self.assertEqual(_detect_platform("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "youtube")

    def test_youtube_short(self):
        self.assertEqual(_detect_platform("https://youtu.be/abc123xyz"), "youtube")

    def test_instagram(self):
        self.assertEqual(_detect_platform("https://www.instagram.com/reel/ABC123/"), "instagram")

    def test_douyin(self):
        self.assertEqual(_detect_platform("https://www.douyin.com/video/123456789"), "douyin")

    def test_twitter(self):
        self.assertEqual(_detect_platform("https://twitter.com/user/status/123456"), "twitter")

    def test_twitter_x(self):
        self.assertEqual(_detect_platform("https://x.com/user/status/123456"), "twitter")

    def test_unknown(self):
        self.assertEqual(_detect_platform("https://example.com/video/123"), "other")

    def test_empty(self):
        self.assertEqual(_detect_platform(""), "other")

    def test_none(self):
        self.assertEqual(_detect_platform(None), "other")


if __name__ == "__main__":
    unittest.main()
