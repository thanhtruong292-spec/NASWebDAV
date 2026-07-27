"""
Regression: background thumbnail daemon must NOT write placeholder on failure.
If _process_one_thumb writes a placeholder file, _needs_thumb() sees size>0
and never retries — that is the root cause of the "50 errors, 0 retry" storm.
"""
import os, sys, tempfile, hashlib, threading

# -- Minimal stubs so we can import nas_api_server pieces without Flask ----
class _FakeLog:
    def info(self, *a, **k): pass
    def warning(self, *a, **k): pass
    def error(self, *a, **k): pass

# Inject stubs before importing nas_api_server
sys.modules.setdefault('flask', type(sys)('flask'))
sys.modules['flask'].request = None
sys.modules['flask'].jsonify = lambda x: x
sys.modules['flask'].Response = lambda *a, **k: None
sys.modules['flask'].Blueprint = lambda *a, **k: None

# --- Test: _process_one_thumb does NOT create placeholder on ffmpeg failure
def test_no_placeholder_on_failure():
    """
    GIVEN: a video file that ffmpeg cannot decode (simulate by missing src)
    WHEN:  _process_one_thumb is called by the background daemon
    THEN:  NO placeholder file is written at thumb_path
          so _needs_thumb() stays True and the file will be retried next scan.
    """
    from nas_api_server import (
        _process_one_thumb,
        _needs_thumb,
        _get_thumb_path,
        MEDIA_VIDEO_EXTS,
        THUMB_DIR_NAME,
        get_webdav_root,
    )

    base_dir = get_webdav_root()
    tmp_dir = tempfile.mkdtemp()
    fake_video = os.path.join(tmp_dir, "corrupt_test_video.mp4")
    # Write a tiny fake file that ffmpeg cannot decode
    with open(fake_video, "wb") as f:
        f.write(b"\x00" * 256)

    thumb_path = _get_thumb_path(base_dir, fake_video)
    os.makedirs(os.path.dirname(thumb_path), exist_ok=True)
    # Remove any old thumb so _needs_thumb starts True
    if os.path.exists(thumb_path):
        os.remove(thumb_path)

    assert _needs_thumb(fake_video), "precondition: thumb should not exist yet"

    ext = os.path.splitext(fake_video)[1].lower()
    ok = _process_one_thumb((fake_video, thumb_path, ext))

    assert ok is False, "ffmpeg on corrupt file should return False"
    assert not os.path.exists(thumb_path), (
        "FAIL: placeholder was written at %s — _needs_thumb() will treat "
        "this file as done and NEVER retry it" % thumb_path
    )
    assert _needs_thumb(fake_video), "_needs_thumb must still be True after failure"

    # Cleanup
    os.remove(fake_video)
    os.rmdir(tmp_dir)
    if os.path.exists(thumb_path):
        os.remove(thumb_path)
    print("PASS: no placeholder on failure")


if __name__ == "__main__":
    test_no_placeholder_on_failure()
