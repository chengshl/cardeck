"""Cleanup when the Gateway is offline; an active Gateway performs its own cleanup."""
import json
import os
from pathlib import Path
from log_archive import ArchiveBusy, LogArchive

settings = json.loads(Path(os.environ.get("CARDECK_LOG_CONFIG", "/etc/cardeck-logs/storage.json")).read_text(encoding="utf-8"))
settings.pop("cleanup_interval_seconds", None)
try:
    store = LogArchive(**settings)
except ArchiveBusy:
    print('{"cleanup":"managed-by-active-gateway"}')
else:
    try:
        print(json.dumps({"cleanup": "complete", "deleted_expired_files": store.deleted_expired}))
    finally:
        store.close()
