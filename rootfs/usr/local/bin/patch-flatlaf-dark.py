#!/usr/bin/env python3
"""Patch JD's installed FlatLaf so the dark theme renders a COMPLETE #161616
monochrome dark — chrome, dialogs, inputs AND the table JProgressBars (download list
+ account traffic). Those bars are FlatLaf JProgressBars whose colours come from
FlatLaf's own defaults at creation time; JD's colorfor* config and a runtime UIManager
agent cannot reach them (the agent's EDT task always runs after JD built the table).
Setting the colours in FlatLaf's own dark properties is the only place that covers
everything, race-free.

Mechanism (the "Material Darker" approach): append FlatLaf property overrides to
com/formdev/flatlaf/FlatDarkLaf.properties inside flatlaf.jar, then update the SHA-256
in flatlaf.dep.json so JD's dependency check accepts the modified jar instead of
re-downloading the stock one.

IMPORTANT: FlatLaf resolves a key like "ProgressBar.foreground = @accentSliderColor"
from a VARIABLE. Overriding the *key* (ProgressBar.foreground = #...) does not win, but
overriding the *variable* (@accentSliderColor = #...) does — same as @background. So the
overrides below set the variables.

Re-patchable: the previous jdp block (if any) is stripped before the current one is
appended, so changing OVERRIDES here takes effect on the next start. Version-agnostic
and idempotent (the versioned marker); no-op until JD has installed flatlaf.jar.
"""
import hashlib
import json
import os
import sys
import zipfile

LAF = os.path.join(os.environ.get("JD_INST_DIR", "/config/JDownloader"), "libs", "laf")
JAR = os.path.join(LAF, "flatlaf.jar")
DEP = os.path.join(LAF, "flatlaf.dep.json")
PROP = "com/formdev/flatlaf/FlatDarkLaf.properties"
BLOCK_PREFIX = "#jdp-carbon-dark"          # any previous block starts with this
MARKER = "#jdp-carbon-dark v5"             # bump when OVERRIDES change -> forces re-patch

# Custom progress-bar UI (compiled in the Docker builder, copied into the image). It is
# injected into flatlaf.jar so it loads in FlatLaf's own classloader, and registered via
# the ProgressBarUI key so the download/account bars stay dark on selected/hover rows
# (FlatLaf paints the fill from the cell foreground, which JD's highlighter turns light).
CLASS_SRC = "/opt/JDownloader/flatlaf-patch/io/github/junkerderprovinz/DarkFillProgressBarUI.class"
CLASS_JARPATH = "io/github/junkerderprovinz/DarkFillProgressBarUI.class"
UI_KEY_LINE = "ProgressBarUI = io.github.junkerderprovinz.DarkFillProgressBarUI"

OVERRIDES = """

#jdp-carbon-dark v5 - complete #161616 monochrome dark (no blue accent).
# The progress-bar/slider fill is @accentSliderColor = if(@accentColor, @accentColor,
# @accentBase2Color), and @accentBase2Color = lighten(... @accentBaseColor ...). FlatLaf
# recomputes the accent at runtime (@accentColor = systemColor(accent)), so overriding
# @accentColor / @accentSliderColor does NOT stick. The MASTER @accentBaseColor DOES and
# cascades: @accentBaseColor -> @accentBase2Color -> @accentSliderColor -> ProgressBar.
# The bar fill on selected/hover rows is forced dark by the injected ProgressBarUI below.
@background = #161616
@foreground = #f4f4f4
@componentBackground = #1e1e1e
@disabledForeground = #6f6f6f
@accentBaseColor = #4d4d4d
@accentColor = #525252
@selectionBackground = #525252
@selectionForeground = #f4f4f4
ProgressBar.background = #262626
ProgressBar.selectionForeground = #f4f4f4
ProgressBar.selectionBackground = #f4f4f4
"""


def log(msg):
    print("[patch-flatlaf] " + msg, file=sys.stderr)


def main():
    if not (os.path.isfile(JAR) and os.path.isfile(DEP)):
        log("flatlaf.jar / dep.json not present yet (first run) - skipping")
        return

    try:
        with zipfile.ZipFile(JAR) as z:
            names = z.namelist()
            if PROP not in names:
                log("FlatDarkLaf.properties missing from jar (layout changed) - skipping")
                return
            props = z.read(PROP).decode("utf-8", "replace")
            if MARKER in props:
                return  # already patched with the current overrides
            entries = [(n, z.read(n)) for n in names]
    except Exception as e:
        log("read failed: %s" % e)
        return

    # The custom UI is only registered if its compiled class is actually present, so a
    # missing/foreign .class can never point ProgressBarUI at an unloadable class (which
    # would crash every JProgressBar). Without it, the bar just stays light on hover.
    class_bytes = None
    try:
        if os.path.isfile(CLASS_SRC):
            class_bytes = open(CLASS_SRC, "rb").read()
    except Exception as e:
        log("could not read custom ProgressBarUI class (%s) - skipping UI override" % e)

    # Drop any previous jdp block, then append the current one (+ the UI key if we have it).
    idx = props.find(BLOCK_PREFIX)
    if idx != -1:
        props = props[:idx].rstrip() + "\n"
    overrides = OVERRIDES + (UI_KEY_LINE + "\n" if class_bytes is not None else "")
    new_props = (props + overrides).encode("utf-8")

    tmp = JAR + ".tmp"
    try:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            for name, data in entries:
                if name == CLASS_JARPATH:
                    continue  # drop a stale copy; re-added fresh below
                z.writestr(name, new_props if name == PROP else data)
            if class_bytes is not None:
                z.writestr(CLASS_JARPATH, class_bytes)
        os.replace(tmp, JAR)
    except Exception as e:
        log("rewrite failed: %s" % e)
        if os.path.exists(tmp):
            os.remove(tmp)
        return

    # JD verifies the jar against installed.hashes["flatlaf.jar"] (SHA-256) and
    # re-downloads on mismatch, so update it to the patched jar's hash.
    try:
        new_sha = hashlib.sha256(open(JAR, "rb").read()).hexdigest()
        with open(DEP) as f:
            dep = json.load(f)
        dep["installed"]["hashes"]["flatlaf.jar"] = new_sha
        with open(DEP, "w") as f:
            json.dump(dep, f)
        ui = "with dark ProgressBarUI" if class_bytes is not None else "without ProgressBarUI (class missing)"
        log("patched FlatDarkLaf -> #161616 dark (v5) %s; dep.json sha256 updated" % ui)
    except Exception as e:
        log("dep.json update failed (JD may re-download stock): %s" % e)


if __name__ == "__main__":
    main()
