#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's GraphicalUserInterfaceSettings.lookAndFeelTheme enum.
# Also sets iconsetid=flat so JD uses JD_Plain-style icons (bundled in JD's classpath).
# Always overwrites — env var wins over anything JD wrote on the previous run.

THEME="${1:-JD_Plain_Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"

log() { echo "[jdownloader-theme] $*"; }

mkdir -p "${JD_CFG}"

case "${THEME}" in
    JD_Plain_Dark|*[Dd][Aa][Rr][Kk]*) LAF="FLATLAF_DARK"  ;;
    JD_Plain)                          LAF="FLATLAF_LIGHT" ;;
    JDDEFAULT)                         LAF="DEFAULT"       ;;
    *)                                 LAF="FLATLAF_DARK"  ;;
esac

log "Theme=${THEME} → lookandfeeltheme=${LAF}"

# Set Look-and-Feel (window chrome)
GUI_JSON="${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json"
python3 - <<PYEOF
import json, os
path = "${GUI_JSON}"
data = {}
if os.path.exists(path):
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        pass
data["lookandfeeltheme"] = "${LAF}"
with open(path, "w") as f:
    json.dump(data, f, indent=2)
print("[jdownloader-theme] lookandfeeltheme=${LAF} → " + path)
PYEOF

# Set icon set to "flat" (= JD_Plain icons bundled in JD's classpath JARs).
# iconsetid is a SEPARATE key from lookandfeeltheme — without it, JD defaults
# to "standard" icons regardless of the LAF selection.
# Written to multiple candidate paths because the exact LAF class name varies
# between JD versions; JD reads whichever file matches its active LAF class.
for LAF_JSON in \
    "${JD_CFG}/org.jdownloader.updatev2.gui.LAFSettings.json" \
    "${JD_CFG}/laf/com.formdev.flatlaf.FlatDarkLaf.json" \
    "${JD_CFG}/laf/FlatDarkLaf.json"; do
python3 - <<PYEOF 2>/dev/null || true
import json, os
path = "${LAF_JSON}"
os.makedirs(os.path.dirname(path), exist_ok=True)
data = {}
if os.path.exists(path):
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        pass
data["iconsetid"] = "flat"
with open(path, "w") as f:
    json.dump(data, f, indent=2)
print("[jdownloader-theme] iconsetid=flat → " + path)
PYEOF
done

log "done"
exit 0
