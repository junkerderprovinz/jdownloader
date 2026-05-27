#!/usr/bin/env python3
"""
Patches FlatLaf's FlatDarkLaf.properties inside the FlatLaf JAR with the
Breeze Dark colour palette so FLATLAF_DARK looks like JD Plain Dark out of
the box — no user-visible install dialog, no separate theme selection.

Usage: patch-flatlaf.py <src.jar> <dst.jar>
"""
import io, os, sys, zipfile

TARGET = "com/formdev/flatlaf/FlatDarkLaf.properties"

# Breeze Dark palette — KDE Breeze Dark / matching JD_Plain_Dark theme.json
BREEZE_DARK = b"""
# ===== JD Plain Dark \xe2\x80\x94 Breeze Dark palette =====
@background=#232629
@foreground=#eff0f1
@disabledForeground=#7f8c8d
@selectionBackground=#3daee9
@selectionForeground=#eff0f1
@selectionInactiveBackground=#2d3237
@menuBackground=#1b1e20
@toolbarBackground=#232629
@tooltipBackground=#31363b
@tooltipForeground=#eff0f1
@borderColor=#2d3237
@separatorColor=#2d3237
@linkColor=#2980b9
@focusColor=#3daee9
@componentBackground=#31363b
@disabledBackground=#2d3237
"""


def patch(src: str, dst: str) -> None:
    buf = io.BytesIO()
    patched = False
    with zipfile.ZipFile(src, "r") as zin, \
         zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == TARGET:
                data += BREEZE_DARK
                patched = True
                print(f"[flatlaf-patch] Patched  {TARGET}")
            zout.writestr(item, data)
        if not patched:
            # FlatLaf version without this file — create it
            zout.writestr(TARGET, BREEZE_DARK.lstrip())
            print(f"[flatlaf-patch] Created  {TARGET} (was absent)")

    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(buf.getvalue())
    print(f"[flatlaf-patch] Written  {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"Usage: {sys.argv[0]} <src.jar> <dst.jar>")
    patch(sys.argv[1], sys.argv[2])
