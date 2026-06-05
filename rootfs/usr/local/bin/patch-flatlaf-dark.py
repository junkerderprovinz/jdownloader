#!/usr/bin/env python3
"""Patch JD's installed FlatLaf so the dark theme renders a COMPLETE #161616
monochrome dark — chrome, dialogs, inputs AND the table JProgressBars (download list
+ account traffic). Those bars are FlatLaf JProgressBars whose colours come from
FlatLaf's own UIManager defaults at creation time; JD's colorfor* config and a runtime
UIManager agent cannot reach them (the agent's EDT task always runs after JD has built
the table). Setting the colours in FlatLaf's own dark properties is the only place that
covers everything, race-free.

Mechanism (the "Material Darker" approach): append FlatLaf property overrides to
com/formdev/flatlaf/FlatDarkLaf.properties inside flatlaf.jar, then update the SHA-256
in flatlaf.dep.json so JD's dependency check accepts the modified jar instead of
re-downloading the stock one.

Idempotent (marker line) and version-agnostic (patches whatever version JD installed).
No-op until JD has actually installed flatlaf.jar (first run downloads it).
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
MARKER = "#jdp-carbon-dark"

OVERRIDES = """

#jdp-carbon-dark - force a complete #161616 monochrome dark (no blue accent)
@background = #161616
@foreground = #f4f4f4
@componentBackground = #1e1e1e
@disabledForeground = #6f6f6f
@accentColor = #525252
@selectionBackground = #525252
@selectionForeground = #f4f4f4
ProgressBar.background = #262626
ProgressBar.foreground = #4d4d4d
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
                return  # already patched
            entries = [(n, z.read(n)) for n in names]
    except Exception as e:
        log("read failed: %s" % e)
        return

    tmp = JAR + ".tmp"
    try:
        new_props = (props + OVERRIDES).encode("utf-8")
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            for name, data in entries:
                z.writestr(name, new_props if name == PROP else data)
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
        log("patched FlatDarkLaf -> #161616 dark; dep.json sha256 updated")
    except Exception as e:
        log("dep.json update failed (JD may re-download stock): %s" % e)


if __name__ == "__main__":
    main()
