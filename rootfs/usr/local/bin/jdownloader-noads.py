#!/usr/bin/env python3
"""
Turns off JDownloader's built-in advertising. The "Become premium user" banner
otherwise fills the right side of the tab row under the download graph; the
graph's own height is a separate toolbar constraint that the dialog agent grows
at runtime.

JD resets this config on first install and on self-update, so this runs before
every JD start, like disable-tray.py.

Only advertising is touched: the banner, the premium-alert columns, the
status-bar "+ premium" button and the special-deal popups. The Donate button and
every functional setting stay as JD or the user set them. The key names are those
of JD's org.jdownloader.settings.GraphicalUserInterfaceSettings.

Usage: jdownloader-noads.py <jd_cfg_dir>
"""
import json
import os
import stat
import sys
from pathlib import Path

NAME = "org.jdownloader.settings.GraphicalUserInterfaceSettings.json"

AD_KEYS = {
    "bannerenabled": False,                       # the bottom "Become premium user" banner
    "statusbaraddpremiumbuttonvisible": False,    # status-bar "+ premium" button
    "premiumalertspeedcolumnenabled": False,      # coloured premium nag in the speed column
    "premiumalerttaskcolumnenabled": False,       # ... task/progress column
    "premiumalertetacolumnenabled": False,        # ... ETA column
    "premiumdisabledwarningflashenabled": False,  # flashing "premium disabled" warning
    "specialdealsenabled": False,                 # special-deal advertisement popups
    "specialdealoboomdialogvisibleonstartup": False,
}


def disable(cfg_dir: str) -> None:
    cfg = Path(cfg_dir)
    cfg.mkdir(parents=True, exist_ok=True)
    path = cfg / NAME

    data = {}
    if path.exists():
        # A previous run may have left it read-only.
        try:
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)
        except OSError:
            pass
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            data = {}

    changed = not path.exists()
    for key, value in AD_KEYS.items():
        if data.get(key) != value:
            data[key] = value
            changed = True

    if changed:
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        print(f"[jd-noads] {NAME} -> ads off (banner + premium alerts + special deals)", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <jd_cfg_dir>")
    disable(sys.argv[1])
