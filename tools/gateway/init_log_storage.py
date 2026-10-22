"""One-time explicit bootstrap of the dedicated hostPath directory."""
import json
import os
from pathlib import Path
from log_archive import initialize_root, verify_mount

share = Path("/shared")
settings = json.loads(Path(os.environ.get("CARDECK_LOG_CONFIG", "/etc/cardeck-logs/storage.json")).read_text(encoding="utf-8"))
expected = settings["expected_mount"]
verify_mount(share, expected)
root = share / "cardeck-private-logs"
if root.is_symlink() or root.resolve().parent != share.resolve():
    raise OSError("Invalid dedicated directory")
root.mkdir(mode=0o700, exist_ok=True)
initialize_root(root, expected)
probe = root / "http-isolation-probe.txt"
if not probe.exists():
    with probe.open("x", encoding="utf-8") as handle:
        handle.write("CarDeck storage isolation check; no device data.\n")
        handle.flush()
        os.fsync(handle.fileno())
print(json.dumps({"initialized": True, "directory": "configured-private-storage", "shared_filesystem": "cifs"}))
