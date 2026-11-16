def _ensure_backup_dir():
    import stat
    try:
        if os.path.realpath(_BACKUP_DIR) != os.path.abspath(_BACKUP_DIR):
            raise ValueError('Backup directory must not use symlinks')
        parent = os.stat(os.path.dirname(_BACKUP_DIR))
        if parent.st_uid != os.geteuid() or parent.st_mode & 0o022:
            raise ValueError('Backup parent is not private to its owner')
        os.makedirs(_BACKUP_DIR, mode=0o700, exist_ok=True)
        fd = os.open(_BACKUP_DIR, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if os.fstat(fd).st_uid != os.geteuid():
                raise ValueError('Unexpected backup directory owner')
            os.fchmod(fd, 0o700)
            for name in os.listdir(_BACKUP_DIR):
                if not name.startswith('Backup_NAS') or not name.endswith('.tar.gz'):
                    continue
                archive_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                try:
                    info = os.fstat(archive_fd)
                    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1:
                        raise ValueError('Unsafe backup archive')
                    os.fchmod(archive_fd, 0o600)
                finally:
                    os.close(archive_fd)
        finally:
            os.close(fd)
        return True
    except Exception as error:
        log.error('[Backup] Private backup directory unavailable: %s', error)
        return False


def _open_private_backup_tar(path):
    import contextlib
    @contextlib.contextmanager
    def create():
        if not _ensure_backup_dir():
            raise IOError('Private backup directory unavailable')
        name = os.path.basename(path)
        if os.path.abspath(os.path.dirname(path)) != os.path.realpath(_BACKUP_DIR) or not name.startswith('Backup_NAS') or not name.endswith('.tar.gz'):
            raise ValueError('Invalid backup archive path')
        directory_fd = os.open(_BACKUP_DIR, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory_fd)
            with os.fdopen(fd, 'wb') as raw:
                with tarfile.open(fileobj=raw, mode='w:gz') as archive:
                    yield archive
                raw.flush()
                os.fsync(raw.fileno())
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    return create()
