#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os

p = '/srv/dev-disk-by-label-data/New folder/.nas_meta/livestream_logs'
if os.path.exists(p):
    files = sorted(
        [os.path.join(p, f) for f in os.listdir(p) if f.endswith('.log')],
        key=os.path.getmtime,
        reverse=True
    )
    if files:
        print("Latest:", files[0])
        with open(files[0], "r", encoding="utf-8", errors="ignore") as fh:
            print(fh.read())
