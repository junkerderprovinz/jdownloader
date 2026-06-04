#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's lookAndFeelTheme AND writes JD's native per-LAF colour
# config (cfg/laf/<LAF>.json). JD's ExtTable / panels / settings read these
# "colorfor*" keys themselves — that is why the content areas go dark WITHOUT
# the old JVM agent (same mechanism the community "Material Darker" theme uses).
#
#   Dark  = JD_Plain (flat) icons + KDE Breeze Dark "colorfor*" colours
#   Light = JD_Plain (flat) icons + JD's default light colours
#
# Always overwrites — env var wins over anything JD wrote on the previous run.

THEME="${1:-JD_Plain_Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"
log() { echo "[jdownloader-theme] $*"; }
mkdir -p "${JD_CFG}/laf"

case "${THEME}" in
    Dark|JD_Plain_Dark|*[Dd][Aa][Rr][Kk]*) LAF="FLATLAF_DARK"  ;;
    Light|JD_Plain)                         LAF="FLATLAF_LIGHT" ;;
    JDDEFAULT)                              LAF="DEFAULT"       ;;
    *)                                      LAF="FLATLAF_DARK"  ;;
esac
log "Theme=${THEME} -> lookandfeeltheme=${LAF}"

# 1) Look-and-Feel (window chrome / Swing) — GraphicalUserInterfaceSettings
python3 - "${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" "${LAF}" <<'PYEOF'
import json, os, sys
path, laf = sys.argv[1], sys.argv[2]
d = {}
if os.path.exists(path):
    try: d = json.load(open(path))
    except Exception: pass
d["lookandfeeltheme"] = laf
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] lookandfeeltheme=%s -> %s" % (laf, path))
PYEOF

# 2) JD's native per-LAF colours + icon set.
if [ "${LAF}" = "FLATLAF_DARK" ]; then
    # JD_Plain (flat) icons + KDE Breeze Dark "colorfor*" palette. JD reads these
    # for the download list, link grabber, settings table, progress bars, etc.
    python3 - "${JD_CFG}/laf/FlatDarkLaf.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
d = {}
if os.path.exists(path):
    try: d = json.load(open(path))
    except Exception: pass
d.update({
    "iconsetid": "flat",
    # panels / config
    "colorforpanelbackground":                    "#ff232629",
    "colorforpanelborders":                       "#ff2d3237",
    "colorforpanelheaderbackground":              "#ff1b1e20",
    "colorforpanelheaderforeground":              "#ffeff0f1",
    "colorforconfigheadertextcolor":              "#ffeff0f1",
    "colorforconfigpaneldescriptiontext":         "#ffeff0f1",
    "configlabelenabledtextcolor":                "#ffeff0f1",
    "configlabeldisabledtextcolor":               "#ff7f8c8d",
    # tables (download list / link grabber)
    "colorfortablepackagerowbackground":          "#ff232629",
    "colorfortablepackagerowforeground":          "#ffeff0f1",
    "colorfortablealternaterowbackground":        "#ff1e2124",
    "colorfortablealternaterowforeground":        "#ffeff0f1",
    "colorfortableselectedrowsbackground":        "#ff3daee9",
    "colorfortableselectedrowsforeground":        "#ff232629",
    "colorfortablemouseoverrowbackground":        "#ff31363b",
    "colorfortablemouseoverrowforeground":        "#ffeff0f1",
    "colorfortablerowgap":                        "#ff2d3237",
    "colorfortablesortedcolumnview":              "#ff31363b",
    "colorfortablefilteredview":                  "#ff2d8a42",
    "colorfortooltipforeground":                  "#ffeff0f1",
    # account / error states
    "colorforerrorforeground":                    "#fff07178",
    "colorforlinkgrabberdupehighlighter":         "#33f07178",
    "colorfortableaccounterrorrowbackground":     "#7ff07178",
    "colorfortableaccounterrorrowforeground":     "#ffeff0f1",
    "colorfortableaccounttemperrorrowbackground": "#7fffcb6b",
    "colorfortableaccounttemperrorrowforeground": "#ffeff0f1",
    # progress bar (Breeze green)
    "colorforprogressbarforeground1":             "#5f2d8a42",
    "colorforprogressbarforeground2":             "#5f2d8a42",
    "colorforprogressbarforeground3":             "#802d8a42",
    "colorforprogressbarforeground4":             "#5f2d8a42",
    "colorforprogressbarforeground5":             "#5f2d8a42",
    # scrollbars
    "colorforscrollbarsnormalstate":              "#ff31363b",
    "colorforscrollbarsmouseoverstate":           "#ff434a52",
    # toggles
    "tablealternaterowhighlightenabled":          True,
    "textantialiasenabled":                       True,
    # No FlatLaf-drawn window title bar: openbox already runs JD's window
    # undecorated + maximised (kiosk). Without this, FlatLaf paints its own
    # title bar back inside the undecorated frame.
    "windowdecorationenabled":                    False,
})
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] Breeze Dark colorfor* + iconsetid=flat -> %s" % path)
PYEOF
else
    # Light: JD_Plain (flat) icons, JD's default light colours.
    python3 - "${JD_CFG}/laf/FlatLightLaf.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
d = {}
if os.path.exists(path):
    try: d = json.load(open(path))
    except Exception: pass
d["iconsetid"] = "flat"
d["windowdecorationenabled"] = False  # no FlatLaf-drawn title bar (kiosk)
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] light: iconsetid=flat -> %s" % path)
PYEOF
fi

log "done"
exit 0
