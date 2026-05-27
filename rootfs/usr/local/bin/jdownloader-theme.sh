#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's GraphicalUserInterfaceSettings.lookAndFeelTheme enum.
# Always overwrites — env var wins over anything JD wrote on the previous run.

THEME="${1:-JD_Plain_Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"

log() { echo "[jdownloader-theme] $*"; }

mkdir -p "${JD_CFG}"

case "${THEME}" in
    JD_Plain_Dark|*[Dd][Aa][Rr][Kk]*) LAF="FLATLAF_DARK"  ;;
    JDDEFAULT)                         LAF="DEFAULT"        ;;
    *)                                 LAF="FLATLAF_LIGHT"  ;;
esac

log "Theme=${THEME} → lookAndFeelTheme=${LAF}"

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
print("[jdownloader-theme] lookAndFeelTheme=${LAF} → " + path)
PYEOF

log "done"
exit 0
