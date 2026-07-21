sed -i "/Environment=\"MALLOC_ARENA_MAX=1\"/a Environment=\"WEBDAV_ROOT=/srv/dev-disk-by-label-data/New folder\"" /etc/systemd/system/nas_api.service
systemctl daemon-reload
systemctl restart nas_api.service
