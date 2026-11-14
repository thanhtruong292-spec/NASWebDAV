<?php
// Shared with the Python coordinator; normal WebDAV mutations remain parallel.
function nas_namespace_guard($lockPath = '/run/naswebdav/namespace.lock') {
    $method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';
    if (in_array($method, array('GET', 'HEAD', 'OPTIONS'), true)) {
        return;
    }
    $parent = @lstat(dirname($lockPath));
    $file = @lstat($lockPath);
    if ($parent === false || $file === false || $parent['uid'] !== 0 ||
        ($parent['mode'] & 0022) !== 0 || ($parent['mode'] & 0170000) !== 0040000 ||
        ($file['mode'] & 0170000) !== 0100000) {
        http_response_code(503);
        header('Retry-After: 1');
        exit('Unsafe NAS namespace lock');
    }
    $lock = @fopen($lockPath, 'rb');
    if ($lock === false) {
        http_response_code(503);
        header('Retry-After: 1');
        exit('NAS namespace lock unavailable');
    }
    $info = fstat($lock);
    if ($info['uid'] !== 0 || ($info['mode'] & 0022) !== 0 ||
        !flock($lock, LOCK_SH | LOCK_NB)) {
        fclose($lock);
        http_response_code(503);
        header('Retry-After: 1');
        exit('NAS cleanup in progress');
    }
    register_shutdown_function(function () use ($lock) {
        flock($lock, LOCK_UN);
        fclose($lock);
    });
}
