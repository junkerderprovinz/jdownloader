#!/usr/bin/env python3
"""
Removes JDownloader's SystemTrayExtension JAR after the bootstrap installs it.

The "system tray isn't supported in this environment" popup is triggered by
the extension's startup probe of java.awt.SystemTray.isSupported() — which
runs as soon as JD loads the extension JAR, regardless of any config that
says the extension is disabled.

The only reliable way to prevent the popup is to keep JD from loading the
JAR at all. This script:

  1. Walks ${JD_DIR}/libs/extensions/ for any *ystemTray*.jar
  2. Renames each match to *.jar.disabled

JD's extension loader skips files without the .jar suffix, so the extension
is never instantiated and the probe never runs.

Usage: kill-tray-extension.py <jd_install_dir>
"""
import sys
from pathlib import Path


def kill(jd_dir: str) -> None:
    ext_dir = Path(jd_dir) / "libs" / "extensions"
    if not ext_dir.is_dir():
        return
    for jar in ext_dir.glob("*ystemTray*.jar"):
        try:
            jar.rename(jar.with_suffix(jar.suffix + ".disabled"))
            print(f"[kill-tray] disabled {jar.name}", flush=True)
        except OSError as e:
            print(f"[kill-tray] failed for {jar.name}: {e}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <jd_install_dir>")
    kill(sys.argv[1])
