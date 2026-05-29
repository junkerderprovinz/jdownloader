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
    flat = Path(themes_dir) / "flat" / "theme.json"
    if not flat.exists():
        print(f"[overlay-dark] not found yet: {flat}", flush=True)
        return
    try:
        data = json.loads(flat.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[overlay-dark] parse failed: {e}", flush=True)
        return
    data["dark"] = True
    if "colors" not in data:
        data["colors"] = {}
    data["colors"].update(DARK_PALETTE)
    flat.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"[overlay-dark] applied to {flat}", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <themes_dir>")
    overlay(sys.argv[1])
