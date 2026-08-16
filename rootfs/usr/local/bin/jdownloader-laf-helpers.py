#!/usr/bin/env python3
"""Shared JD_THEME / LAF helpers used by autostart + local unit tests.

CLI:
  resolve <theme>     -> prints KEY=VALUE lines (laf, expect_laf, expect_classic, is_classic)
  jar-valid <path> <entry>  -> exit 0 if zip readable and entry present
  should-heal-classic --classic 0|1 --matches 0|1 --synthetica 0|1 --license 0|1 --flatlaf 0|1
"""
from __future__ import annotations

import json
import os
import sys
import zipfile


def normalize_theme(theme: str | None) -> str:
    return (theme or "Dark").strip().lower()


def is_classic_theme(theme: str | None) -> bool:
    return normalize_theme(theme) == "jddefault"


def resolve_jd_theme(theme: str | None) -> dict:
    """Map JD_THEME to LAF expectation. Accepts any casing of JDDEFAULT."""
    t = normalize_theme(theme)
    if t == "jddefault":
        return {
            "laf": "DEFAULT",
            "expect_laf": "",
            "expect_classic": "1",
            "is_classic": True,
        }
    if t in ("light", "jd_plain"):
        return {
            "laf": "FLATLAF_LIGHT",
            "expect_laf": "flatlight",
            "expect_classic": "0",
            "is_classic": False,
        }
    if "dark" in t:
        return {
            "laf": "FLATLAF_DARK",
            "expect_laf": "flatdark",
            "expect_classic": "0",
            "is_classic": False,
        }
    return {
        "laf": "FLATLAF_DARK",
        "expect_laf": "flatdark",
        "expect_classic": "0",
        "is_classic": False,
    }


FLATLAF_ENTRY = "com/formdev/flatlaf/FlatDarkLaf.class"
# LookAndFeelType.DEFAULT uses Synthetica base; skins live in synthetica-themes.
# synthetica.jar ships the core LAF classes (see libs/laf/synthetica.dep.json).
SYNTHETICA_ENTRY = "de/javasoft/plaf/synthetica/SyntheticaLookAndFeel.class"

# Citation: org.jdownloader.updatev2.gui.LookAndFeelType —
#   optional Synthetica skins -> extensionID "synthetica-themes"
#   LookAndFeelType.DEFAULT -> extensionID null (no GUI-driven install ID)
# Citation: ressourcen/libs/laf/synthetica.dep.json — package id "synthetica" for synthetica.jar
SYNTHETICA_REQUEST_IDS = ("synthetica", "synthetica-themes")


def jar_valid(path: str, required_entry: str) -> bool:
    try:
        with zipfile.ZipFile(path) as z:
            z.getinfo(required_entry)
            return z.testzip() is None
    except Exception:
        return False


def flatlaf_jar_valid(path: str) -> bool:
    return jar_valid(path, FLATLAF_ENTRY)


def synthetica_jar_valid(path: str) -> bool:
    return jar_valid(path, SYNTHETICA_ENTRY)


def any_valid_synthetica(laf_dir: str) -> bool:
    if not os.path.isdir(laf_dir):
        return False
    for name in os.listdir(laf_dir):
        if name.startswith("synthetica") and name.endswith(".jar"):
            if synthetica_jar_valid(os.path.join(laf_dir, name)):
                return True
    return False


def deregister_extensions(installed_path: str, ids_to_remove: list[str]) -> list[str]:
    """Remove extension IDs from extensions.installed.json; return remaining list."""
    ids: list = []
    if os.path.exists(installed_path):
        try:
            ids = json.load(open(installed_path))
            if not isinstance(ids, list):
                ids = []
        except Exception:
            ids = []
    changed = False
    for ext in ids_to_remove:
        if ext in ids:
            ids.remove(ext)
            changed = True
    if changed:
        parent = os.path.dirname(installed_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        json.dump(ids, open(installed_path, "w"))
    return ids


def seed_extension_requests(request_path: str, ids_to_add: list[str]) -> list[str]:
    ids: list = []
    if os.path.exists(request_path):
        try:
            ids = json.load(open(request_path))
            if not isinstance(ids, list):
                ids = []
        except Exception:
            ids = []
    changed = False
    for ext in ids_to_add:
        if ext not in ids:
            ids.append(ext)
            changed = True
    if changed:
        parent = os.path.dirname(request_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        json.dump(ids, open(request_path, "w"))
    return ids


def should_increment_classic_mismatch(
    *,
    expect_classic: bool,
    laf_matches: bool,
    has_valid_synthetica: bool,
    has_license: bool,
    flatlaf_present_or_parked: bool,
) -> bool:
    """Gate for autostart healer: never kill JD while waiting for first Synthetica download.

    Increment mismatch only when classic is ready to apply (valid jar + license) but LAF
    still wrong. Parked FlatLaf alone must NOT trigger heal.
    """
    del flatlaf_present_or_parked  # intentional: parked jar alone must not heal
    if not expect_classic or laf_matches:
        return False
    return bool(has_valid_synthetica and has_license)


def ensure_hash_prefix(value: str) -> str:
    v = value.strip()
    if not v or v.startswith("#"):
        return v
    return "#" + v


def is_flatlaf_install_prompt(title: str, body: str) -> bool:
    """Tight Look&Feel install matcher (agent contract)."""
    t = (title or "").lower()
    b = (body or "").lower()
    if "about" in t or "über" in t or "uber" in t:
        return False
    not_installed = (
        "not installed" in b
        or "nicht installiert" in b
        or "is not installed" in b
    )
    wants_install = (
        "install it now" in b
        or "installieren" in b
        or "do you want to install" in b
    )
    mentions = (
        "flatlaf_dark" in b
        or "flatlaf_light" in b
        or "flatlaf dark" in b
        or "flatlaf light" in b
    )
    return not_installed and wants_install and mentions


def _cmd_resolve(theme: str) -> int:
    r = resolve_jd_theme(theme)
    for k, v in r.items():
        if isinstance(v, bool):
            v = "1" if v else "0"
        print(f"{k}={v}")
    return 0


def _cmd_jar_valid(path: str, entry: str) -> int:
    return 0 if jar_valid(path, entry) else 1


def _cmd_should_heal(argv: list[str]) -> int:
    flags = {
        "classic": False,
        "matches": False,
        "synthetica": False,
        "license": False,
        "flatlaf": False,
    }
    i = 0
    while i < len(argv):
        a = argv[i]
        if a.startswith("--") and i + 1 < len(argv):
            key = a[2:]
            flags[key] = argv[i + 1] in ("1", "true", "True", "yes")
            i += 2
        else:
            i += 1
    ok = should_increment_classic_mismatch(
        expect_classic=flags["classic"],
        laf_matches=flags["matches"],
        has_valid_synthetica=flags["synthetica"],
        has_license=flags["license"],
        flatlaf_present_or_parked=flags["flatlaf"],
    )
    return 0 if ok else 1


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    cmd = argv[1]
    if cmd == "resolve":
        return _cmd_resolve(argv[2] if len(argv) > 2 else "Dark")
    if cmd == "jar-valid":
        return _cmd_jar_valid(argv[2], argv[3])
    if cmd == "flatlaf-valid":
        return 0 if flatlaf_jar_valid(argv[2]) else 1
    if cmd == "synthetica-valid":
        return 0 if synthetica_jar_valid(argv[2]) else 1
    if cmd == "any-synthetica-valid":
        return 0 if any_valid_synthetica(argv[2]) else 1
    if cmd == "should-heal-classic":
        return _cmd_should_heal(argv[2:])
    if cmd == "seed-synthetica":
        before = []
        path = argv[2]
        if os.path.exists(path):
            try:
                before = json.load(open(path))
            except Exception:
                before = []
        after = seed_extension_requests(path, list(SYNTHETICA_REQUEST_IDS))
        if after != before:
            print(
                "[jdownloader-autostart] requested synthetica extension(s) for classic JDDEFAULT"
                " (ids: synthetica + synthetica-themes; see LookAndFeelType / synthetica.dep.json)",
                file=sys.stderr,
            )
        print("ok")
        return 0
    if cmd == "deregister-synthetica":
        deregister_extensions(argv[2], list(SYNTHETICA_REQUEST_IDS))
        print("ok")
        return 0
    print("unknown command", cmd, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
