"""Add only an index; never replace or remove catalogue rows."""
import json
import os
import sqlite3
import time

path = '/var/lib/nas_fast_index.db'
space = os.statvfs(os.path.dirname(path))
assert space.f_bavail * space.f_frsize > 256 * 1024 * 1024, 'Insufficient free space for safe index creation'
connection = sqlite3.connect(path, timeout=30)
try:
    columns = set(row[1] for row in connection.execute('PRAGMA table_info(file_index)'))
    assert {'path', 'name', 'norm_name', 'is_dir', 'size', 'mtime', 'mime'}.issubset(columns)
    started = time.monotonic()
    connection.execute('CREATE INDEX IF NOT EXISTS idx_file_search_order ON file_index(is_dir DESC,name ASC,norm_name)')
    connection.commit()
    plan = connection.execute('EXPLAIN QUERY PLAN SELECT path,name,is_dir,size,mtime,mime FROM file_index WHERE norm_name LIKE ? ORDER BY is_dir DESC,name ASC LIMIT 300', ('%mp4%',)).fetchall()
    assert not any('TEMP B-TREE' in row[3] for row in plan)
    print(json.dumps({'seconds': round(time.monotonic()-started,2), 'plan': plan}))
finally:
    connection.close()
