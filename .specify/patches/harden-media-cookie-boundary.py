"""Prepare and check a separate candidate; never deploy or import the NAS daemon."""
import ast
import hashlib
import json
import os
import sys
import tempfile
import types


def prepare(source_path, candidate_path):
    with open(source_path, 'rb') as handle:
        original = handle.read()
    expected = 'f79b6ab6a379969a8b76c9d8f1e2ad2cf7098baadf8e3cfde55c87938ff0ca92'
    assert hashlib.sha256(original).hexdigest() == expected, 'Unexpected source revision'
    text = original.decode('utf-8')
    helper_start = text.index('def _direct_flv_has_remuxable_video(')
    before, helper = text[:helper_start], text[helper_start:]
    helper = helper.replace('    flv_url_lower = (flv_url or "").lower()\n', '', 1)
    begin = helper.index('    # TikTok CDN URLs')
    end = helper.index('    cmd.append(flv_url)', begin)
    helper = (helper[:begin] +
              '    # Signed media URLs must not receive browser cookies.\n' +
              '    cmd.extend(["-headers", "Referer: https://www.tiktok.com/\\r\\n"])\n' +
              helper[end:])
    text = before + helper
    loop_start = text.index('            loop_script = """import sys, time, subprocess')
    begin = text.index('    cookie_header = ""\n', loop_start)
    marker = '    cmd.extend(["-headers", "Referer: https://www.tiktok.com/" + crlf + cookie_header])\n'
    end = text.index(marker, begin) + len(marker)
    text = (text[:begin] + '    crlf = chr(13) + chr(10)\n' +
            '    cmd.extend(["-headers", "Referer: https://www.tiktok.com/" + crlf])\n' + text[end:])
    tree = ast.parse(text)
    compile(text, candidate_path, 'exec')  # syntax validation only
    helper = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                  and node.name == '_direct_flv_has_remuxable_video')
    record = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                  and node.name == 'api_livestream_record')
    loop = next(node.value.s for node in ast.walk(record)
                if isinstance(node, ast.Assign) and isinstance(node.value, ast.Str)
                and any(isinstance(target, ast.Name) and target.id == 'loop_script'
                        for target in node.targets))
    ast.parse(loop)
    assert 'Cookie:' not in loop, 'Raw media cookie header remains in generated recorder'
    commands = []
    fake_process = types.SimpleNamespace(
        PIPE=-1, run=lambda command, **kwargs: commands.append(command) or
        types.SimpleNamespace(returncode=0, stdout=b'h264', stderr=b''))
    log = types.SimpleNamespace(info=lambda *args: None, warning=lambda *args: None)
    ns = dict(subprocess=fake_process, log=log)
    module = ast.Module(body=[helper])
    if hasattr(module, 'type_ignores'):
        module.type_ignores = []
    exec(compile(module, candidate_path, 'exec'), ns)
    # The production helper cannot read a cookie jar: no filesystem API is supplied.
    with tempfile.TemporaryDirectory(prefix='media-cookie-check-') as work:
        secret = os.path.join(work, 'cookies.txt')
        with open(secret, 'w') as handle:
            handle.write('SESSION_SENTINEL_NOT_FOR_MEDIA')
        for url in ('https://media.example.invalid/video.flv',
                    'https://pull.example.tiktokcdn-us.com/video.flv?signature=test'):
            assert ns['_direct_flv_has_remuxable_video'](url, secret, 'Test-UA')
    assert len(commands) == 2
    for command in commands:
        assert not any('Cookie:' in item or 'SESSION_SENTINEL' in item for item in command)
        assert command[-1].startswith('https://')
    with open(candidate_path, 'x', encoding='utf-8') as handle:
        handle.write(text)
    print(json.dumps({'candidate_sha256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
                      'probe_cookie_boundary': 'passed',
                      'generated_recorder_without_cookie_header': 'passed',
                      'python': sys.version.split()[0], 'deployed': False}))


if __name__ == '__main__':
    prepare(sys.argv[1], sys.argv[2])
