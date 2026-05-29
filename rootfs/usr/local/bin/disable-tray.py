#!/usr/bin/env python3
"""
Disables JD's system-tray extension before JD starts.
JD creates / re-creates tray config files on first run, so this must run
before every JD start (not only once at container init).

Usage: disable-tray.py <jd_cfg_dir>
"""
import json, os, stat, sys
from pathlib import Path


def disable(cfg_dir: str) -> None:
    cfg = Path(cfg_dir)
    cfg.mkdir(parents=True, exist_ok=True)
    for name in (
        "org.jdownloader.gui.jdtrayicon.TrayExtension.json",
        "org.jdownloader.gui.jdtrayicon.TrayExtensionConfig.json",
    ):
        path = cfg / name
        data = {}
        if path.exists():
            # Force writable in case a prior chmod 444 (autostart hardening)
            # left the file unwriteable from a previous container instance.
            try:
                os.chmod(path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)
            except OSError:
                pass
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception:
                pass
        data["enabled"] = False
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        print(f"[disable-tray] {name} → enabled=false", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <jd_cfg_dir>")
    disable(sys.argv[1])
