#!/usr/bin/env python3
"""
Overlays Breeze Dark colours onto JDownloader's themes/flat/theme.json.

JD's ExtTable (the download list and link grabber) reads its background colour
from the active iconset's theme.json — NOT from the Swing LAF. With iconsetid=flat
(JD Plain icons), JD reads themes/flat/theme.json which ships with LIGHT colours,
making the download list white even when FlatLaf Dark is active.

This script merges our dark palette into themes/flat/theme.json so the colours
match the dark Look & Feel. Icons are unchanged.

Usage: overlay-dark-colors.py <themes_dir>
"""
import json
import sys
from pathlib import Path

DARK_PALETTE = {
    "backgroundColor":          "#232629",
    "backgroundColorHover":     "#31363b",
    "alternateRowBackground":   "#1e2124",
    "foregroundColor":          "#eff0f1",
    "disabledForegroundColor":  "#7f8c8d",
    "selectionBackground":      "#3daee9",
    "selectionForeground":      "#eff0f1",
    "linkColor":                "#2980b9",
    "borderColor":              "#2d3237",
    "panelBackground":          "#31363b",
    "headerBackground":         "#1b1e20",
    "headerForeground":         "#eff0f1",
    "toolbarBackground":        "#232629",
    "menuBackground":           "#1b1e20",
    "menuForeground":           "#eff0f1",
    "buttonBackground":         "#31363b",
    "buttonForeground":         "#eff0f1",
    "tableBackground":          "#232629",
    "tableSelectionBackground": "#3daee9",
    "tableSelectionForeground": "#eff0f1",
    "progressBarBackground":    "#31363b",
    "progressBarForeground":    "#2d8a42",
    "tooltipBackground":        "#31363b",
    "tooltipForeground":        "#eff0f1",
    "focusColor":               "#3daee9",
    "activeTabBackground":      "#232629",
    "inactiveTabBackground":    "#1b1e20",
    "activeTabForeground":      "#eff0f1",
    "inactiveTabForeground":    "#a0a8b0",
}


def overlay(themes_dir: str) -> None:
    flat_dir = Path(themes_dir) / "flat"
    flat = flat_dir / "theme.json"

    # Create themes/flat/ if missing — JD's iconset-flat extension installs
    # icons here but does not ship a theme.json, so JD's custom widgets fall
    # back to default light colours unless we put one in place.
    flat_dir.mkdir(parents=True, exist_ok=True)

    data = {}
    if flat.exists():
        # Force writable in case prior write left it read-only
        import os, stat
        try:
            os.chmod(flat, stat.S_IRUSR | stat.S_IWUSR | stat.S_IRGRP | stat.S_IROTH)
        except OSError:
            pass
        try:
            data = json.loads(flat.read_text(encoding="utf-8"))
        except Exception:
            data = {}

    data.setdefault("id", "flat")
    data.setdefault("name", "JD Plain (Breeze Dark overlay)")
    data["dark"] = True
    if "colors" not in data:
        data["colors"] = {}
    data["colors"].update(DARK_PALETTE)

    flat.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"[overlay-dark] wrote {flat}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <themes_dir>")
    overlay(sys.argv[1])
