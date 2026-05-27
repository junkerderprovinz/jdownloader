#!/usr/bin/env python3
"""
Creates JD_Plain_Dark theme based on the installed JD_Plain theme.

JDownloader 2 theme structure:
  ${JD_DIR}/themes/
    JD_Plain/          ← source (installed by JD on first Settings → Theme open)
      theme.json
      icons/
        16x16/ ...
        32x32/ ...
    JD_Plain_Dark/     ← we create this
      theme.json       ← same but with dark colour overrides
      icons/ ...       ← symlinked / hard-copied from JD_Plain

Note: JD's LAF (FlatDarkLaf) controls the actual window chrome colours.
      This theme overrides JD's own colour tokens (e.g. link colour,
      table row background, tab header) that live in theme.json.
"""
import os, sys, json, shutil, zipfile
from pathlib import Path

JD_DIR    = os.environ.get("JD_INST_DIR", "/config/JDownloader")
THEMES    = Path(JD_DIR) / "themes"
SRC       = "JD_Plain"
DST       = "JD_Plain_Dark"

# JDownloader colour tokens that can be overridden in theme.json
DARK_PALETTE = {
    "backgroundColor":              "#1e1f22",
    "backgroundColorHover":         "#2b2d30",
    "alternateRowBackground":       "#252628",
    "foregroundColor":              "#dfe1e5",
    "disabledForegroundColor":      "#6c7280",
    "selectionBackground":          "#2d5986",
    "selectionForeground":          "#dfe1e5",
    "linkColor":                    "#589df6",
    "borderColor":                  "#43454a",
    "panelBackground":              "#1e1f22",
    "headerBackground":             "#27292e",
    "headerForeground":             "#dfe1e5",
    "toolbarBackground":            "#2b2d30",
    "menuBackground":               "#1e1f22",
    "menuForeground":               "#dfe1e5",
    "buttonBackground":             "#3c3f41",
    "buttonForeground":             "#dfe1e5",
    "tableBackground":              "#1e1f22",
    "tableSelectionBackground":     "#2d5986",
    "tableSelectionForeground":     "#dfe1e5",
    "progressBarBackground":        "#2b2d30",
    "progressBarForeground":        "#4e9f3d",
    "tooltipBackground":            "#2b2d30",
    "tooltipForeground":            "#dfe1e5",
}


def locate_source() -> tuple[str, Path] | None:
    """Return ('dir', path) or ('zip', path) for the JD_Plain source, or None."""
    d = THEMES / SRC
    if d.is_dir():
        return ("dir", d)
    for suffix in (f"{SRC}.zip", f"{SRC}.jar"):
        z = THEMES / suffix
        if z.is_file():
            return ("zip", z)
    # JD also bundles themes in its JAR tree
    for candidate in Path(JD_DIR).rglob(f"*{SRC}*.zip"):
        if SRC in candidate.name:
            return ("zip", candidate)
    return None


def write_theme_json(dst_dir: Path, base: dict | None = None) -> None:
    data = dict(base) if base else {}
    data.update({
        "id":      DST,
        "name":    "JD Plain Dark",
        "dark":    True,
        "version": int(data.get("version", 1)),
        "author":  data.get("author", "junkerderprovinz"),
    })
    if "colors" not in data:
        data["colors"] = {}
    data["colors"].update(DARK_PALETTE)
    with open(dst_dir / "theme.json", "w") as f:
        json.dump(data, f, indent=2)
    print(f"[dark-theme] theme.json written to {dst_dir}")


def from_dir(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
    base = {}
    tj = dst / "theme.json"
    if tj.exists():
        try:
            with open(tj) as f:
                base = json.load(f)
        except Exception:
            pass
    write_theme_json(dst, base)


def from_zip(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    with zipfile.ZipFile(src) as z:
        z.extractall(dst)
    # ZIP might extract into a sub-dir – flatten if needed
    entries = list(dst.iterdir())
    if len(entries) == 1 and entries[0].is_dir():
        for item in entries[0].iterdir():
            item.rename(dst / item.name)
        entries[0].rmdir()
    base = {}
    tj = dst / "theme.json"
    if tj.exists():
        try:
            with open(tj) as f:
                base = json.load(f)
        except Exception:
            pass
    write_theme_json(dst, base)


def minimal_stub(dst: Path) -> None:
    """JD_Plain not installed yet — write a stub; icons fall back to JD_Plain at runtime."""
    dst.mkdir(parents=True, exist_ok=True)
    write_theme_json(dst, {"parent": SRC})
    print(f"[dark-theme] Stub created (JD_Plain not installed yet, icons from {SRC} at runtime)")


def main() -> int:
    THEMES.mkdir(parents=True, exist_ok=True)
    dst = THEMES / DST

    src_info = locate_source()
    if src_info:
        kind, src = src_info
        print(f"[dark-theme] Found {SRC} ({kind}) → {src}")
        if kind == "dir":
            from_dir(src, dst)
        else:
            from_zip(src, dst)
    else:
        minimal_stub(dst)

    print(f"[dark-theme] {DST} ready at {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
