#!/usr/bin/env python3
"""
Disables JDownloader's SystemTrayExtension jar after the bootstrap installs it.

The extension probes java.awt.SystemTray.isSupported() as soon as JD loads the
jar, whatever its config says, and raises the "system tray isn't supported"
popup. Each *ystemTray*.jar in libs/extensions is renamed to *.jar.disabled,
which JD's extension loader skips.

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
