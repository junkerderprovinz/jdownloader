#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's lookAndFeelTheme AND writes JD's native per-LAF colour
# config (cfg/laf/<LAF>.json). JD's ExtTable / panels / settings read these
# "colorfor*" keys themselves — that is why the content areas go dark WITHOUT
# the old JVM agent (same mechanism the community "Material Darker" theme uses).
#
#   Dark  = JD_Plain (flat) icons + IBM Carbon #161616 monochrome "colorfor*" colours
#   Light = JD_Plain (flat) icons + JD's default light colours
#
# Always overwrites — env var wins over anything JD wrote on the previous run.

THEME="${1:-Dark}"
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
    # JD_Plain (flat) icons + IBM Carbon #161616 "colorfor*" palette. JD reads these
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
    # IBM Carbon grayscale — pure monochrome dark, #161616 base, NO colour accent.
    # panels / config
    "colorforpanelbackground":                    "#ff161616",
    "colorforpanelborders":                       "#ff393939",
    "colorforpanelheaderbackground":              "#ff0b0b0b",
    "colorforpanelheaderforeground":              "#fff4f4f4",
    "colorforconfigheadertextcolor":              "#fff4f4f4",
    "colorforconfigpaneldescriptiontext":         "#fff4f4f4",
    "configlabelenabledtextcolor":                "#fff4f4f4",
    "configlabeldisabledtextcolor":               "#ff6f6f6f",
    # tables (download list / link grabber)
    "colorfortablepackagerowbackground":          "#ff161616",
    "colorfortablepackagerowforeground":          "#fff4f4f4",
    "colorfortablealternaterowbackground":        "#ff1e1e1e",
    "colorfortablealternaterowforeground":        "#fff4f4f4",
    "colorfortableselectedrowsbackground":        "#ff525252",
    "colorfortableselectedrowsforeground":        "#fff4f4f4",
    "colorfortablemouseoverrowbackground":        "#ff2a2a2a",
    "colorfortablemouseoverrowforeground":        "#fff4f4f4",
    "colorfortablerowgap":                        "#ff161616",  # = base; a lighter gap shows as a pale top-border on rows
    "colorfortablesortedcolumnview":              "#ff262626",
    "colorfortablefilteredview":                  "#ffa8a8a8",
    "colorfortooltipforeground":                  "#fff4f4f4",
    # account / error states — kept a MUTED red/amber so failed downloads/accounts
    # stay visible (the only non-grey colours in the theme).
    "colorforerrorforeground":                    "#fffa4d56",
    "colorforlinkgrabberdupehighlighter":         "#33fa4d56",
    "colorfortableaccounterrorrowbackground":     "#7ffa4d56",
    "colorfortableaccounterrorrowforeground":     "#fff4f4f4",
    "colorfortableaccounttemperrorrowbackground": "#7ff1c21b",
    "colorfortableaccounttemperrorrowforeground": "#fff4f4f4",
    # progress bar — grey (no colour)
    "colorforprogressbarforeground1":             "#5f8d8d8d",
    "colorforprogressbarforeground2":             "#5f8d8d8d",
    "colorforprogressbarforeground3":             "#808d8d8d",
    "colorforprogressbarforeground4":             "#5f8d8d8d",
    "colorforprogressbarforeground5":             "#5f8d8d8d",
    # scrollbars
    "colorforscrollbarsnormalstate":              "#ff393939",
    "colorforscrollbarsmouseoverstate":           "#ff525252",
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
