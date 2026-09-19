#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's lookAndFeelTheme and writes JD's native per-LAF colour config
# (cfg/laf/<LAF>.json). JD's tables, panels and settings read these "colorfor*" keys
# themselves, which is how the content areas go dark; the community "Material
# Darker" theme works the same way. The standalone desktop port of this palette,
# https://github.com/junkerderprovinz/jd-plain-dark, has the same Carbon colours
# without the kiosk-only windowdecorationenabled=false and should stay in sync.
#
#   Dark  = JD_Plain (flat) icons and the IBM Carbon #161616 monochrome palette
#   Light = JD_Plain (flat) icons and JD's default light colours
#
# The file is always overwritten, since the env var wins over whatever JD wrote on
# the previous run, and then locked read-only like the tray cfg in autostart. JD's
# bootstrap installer can reset it to the stock light colours during its own
# startup, after this script ran, so a rewrite before launch alone loses that race
# on some boots (jdownloader#16). This script runs as root and is not stopped by the
# 444; JD's JVM runs as the unprivileged PUID user and is.

THEME="${1:-Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"
log() { echo "[jdownloader-theme] $*"; }
mkdir -p "${JD_CFG}/laf"

# Unlock what a previous run locked, so switching themes works.
chmod 644 "${JD_CFG}/laf/"*.json 2>/dev/null || true
chmod 644 "${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" 2>/dev/null || true

# The helper resolves every casing of JDDEFAULT the same way autostart does.
HELPERS="/usr/local/bin/jdownloader-laf-helpers.py"
if [ -f "${HELPERS}" ]; then
    LAF=$(python3 "${HELPERS}" resolve "${THEME}" | awk -F= '/^laf=/{print $2; exit}')
else
    THEME_LC=$(printf '%s' "${THEME}" | tr '[:upper:]' '[:lower:]')
    case "${THEME_LC}" in
        jddefault) LAF="DEFAULT" ;;
        light|jd_plain) LAF="FLATLAF_LIGHT" ;;
        *dark*) LAF="FLATLAF_DARK" ;;
        *) LAF="FLATLAF_DARK" ;;
    esac
fi
: "${LAF:=FLATLAF_DARK}"
log "Theme=${THEME} -> lookandfeeltheme=${LAF}"

# The look and feel for the window chrome, in GraphicalUserInterfaceSettings.
python3 - "${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" "${LAF}" <<'PYEOF'
import json, os, sys
path, laf = sys.argv[1], sys.argv[2]
d = {}
if os.path.exists(path):
    try: d = json.load(open(path))
    except Exception: pass
d["lookandfeeltheme"] = laf
# Classic DEFAULT must not keep a flat icon set left from a Dark or Light run.
if laf == "DEFAULT":
    d.pop("iconsetid", None)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] lookandfeeltheme=%s -> %s" % (laf, path))
PYEOF

# JD's native per-LAF colours and icon set.
if [ "${LAF}" = "FLATLAF_DARK" ]; then
    # JD reads the "colorfor*" palette for the download list, link grabber, settings
    # table, progress bars and more.
    python3 - "${JD_CFG}/laf/FlatDarkLaf.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
# A fresh dict rather than a merge, so values for keys no longer set cannot linger
# (an old grey speed-meter graph, for one). JD fills every omitted key with its own
# default.
d = {
    "iconsetid": "flat",
    # IBM Carbon greys: monochrome dark on a #161616 base with no colour accent.
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
    "colorfortablealternaterowbackground":        "#ff161616",  # same as the base, so rows are uniform
    "colorfortablealternaterowforeground":        "#fff4f4f4",
    "colorfortableselectedrowsbackground":        "#ff525252",
    "colorfortableselectedrowsforeground":        "#fff4f4f4",
    "colorfortablemouseoverrowbackground":        "#ff0b0b0b",  # hover is darker than the base
    "colorfortablemouseoverrowforeground":        "#fff4f4f4",
    "colorfortablerowgap":                        "#ff161616",  # same as the base; a lighter gap shows as a pale top border
    "colorfortablesortedcolumnview":              "#ff262626",
    "colorfortablefilteredview":                  "#ffa8a8a8",
    "colorfortooltipforeground":                  "#fff4f4f4",
    # Account and error states keep a muted red and amber, the only colours that are
    # not grey, so failed downloads and accounts stay visible.
    "colorforerrorforeground":                    "#fffa4d56",
    "colorforlinkgrabberdupehighlighter":         "#33fa4d56",
    "colorfortableaccounterrorrowbackground":     "#7ffa4d56",
    "colorfortableaccounterrorrowforeground":     "#fff4f4f4",
    "colorfortableaccounttemperrorrowbackground": "#7ff1c21b",
    "colorfortableaccounttemperrorrowforeground": "#fff4f4f4",
    # The Account Manager's traffic-left bar is a legacy Synthetica JProgressBar with
    # hard-coded white text and no theme key for it (Material Darker uses a saturated
    # fill for the same reason). Unset, it goes through FlatLaf, which fills it with
    # @accentBaseColor: white on light, flickering from dark to light when the tab
    # opens. The lightest fixed grey that keeps the white text readable avoids both.
    # The FlatLaf download and progress bars do not read these keys.
    "colorforprogressbarforeground1":             "#ff606060",
    "colorforprogressbarforeground2":             "#ff666666",
    "colorforprogressbarforeground3":             "#ff6c6c6c",
    "colorforprogressbarforeground4":             "#ff666666",
    "colorforprogressbarforeground5":             "#ff606060",
    # The speed meter keeps JD's green graph (the current, average and limiter keys
    # are omitted); only its text turns light for the dark panel.
    "colorforspeedmetertext":                     "#fff4f4f4",
    "colorforspeedmeteraveragetext":              "#ffb0b0b0",
    # scrollbars
    "colorforscrollbarsnormalstate":              "#ff393939",
    "colorforscrollbarsmouseoverstate":           "#ff525252",
    # toggles
    "tablealternaterowhighlightenabled":          False,  # uniform rows, no stripes
    "textantialiasenabled":                       True,
    # Openbox already runs JD undecorated and maximised; without this FlatLaf paints
    # its own title bar inside the frame.
    "windowdecorationenabled":                    False,
}
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] Carbon #161616 colorfor* + iconsetid=flat -> %s" % path)
PYEOF
    # Keeps JD's unprivileged process from resetting the file (see the header).
    chmod 444 "${JD_CFG}/laf/FlatDarkLaf.json" 2>/dev/null || true
elif [ "${LAF}" = "FLATLAF_LIGHT" ]; then
    python3 - "${JD_CFG}/laf/FlatLightLaf.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
# A fresh dict, for the same reason as in the dark branch.
d = {"iconsetid": "flat", "windowdecorationenabled": False}  # no FlatLaf title bar (kiosk)
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] light: iconsetid=flat -> %s" % path)
PYEOF
    chmod 444 "${JD_CFG}/laf/FlatLightLaf.json" 2>/dev/null || true
else
    # Classic Synthetica (LookAndFeelType.DEFAULT). A light palette keeps the
    # LAFOptions progress and text colours from being null, which would mean an NPE
    # in CustomProgressbarPainter and grey dialog text.
    python3 - "${JD_CFG}/laf/JDDefaultLookAndFeel.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
# The classic keys overwrite an existing file, whose other keys stay.
d = {}
if os.path.exists(path):
    try:
        with open(path) as f:
            existing = json.load(f)
        if isinstance(existing, dict):
            d.update(existing)
    except Exception:
        pass
classic = {
    "configlabelenabledtextcolor": "#FF202020",
    "configlabeldisabledtextcolor": "#FFA0A0A0",
    "colorforconfigheadertextcolor": "#FF202020",
    "colorforconfigpaneldescriptiontext": "#FF808080",
    "colorforpanelheaderforeground": "#FF000000",
    "colorforpanelheaderbackground": "#ffD7E7F0",
    "colorforpanelbackground": "#ffF5FCFF",
    # #aRGB as in the LAFSettings docs (#ffFF0000), which HexColorString accepts.
    "colorforprogressbarforeground1": "#5F70CCFF",
    "colorforprogressbarforeground2": "#5F80C7F7",
    "colorforprogressbarforeground3": "#8078C0EF",
    "colorforprogressbarforeground4": "#5F80C7F7",
    "colorforprogressbarforeground5": "#5F70CCFF",
    "colorfortableselectedrowsbackground": "#ffCAE8FA",
    "colorfortablemouseoverrowbackground": "#ffC9E0ED",
    "colorfortablepackagerowbackground": "#FFDEE7ED",
    "colorforscrollbarsnormalstate": "#ffD7E7F0",
    "colorforscrollbarsmouseoverstate": "#ffABC7D8",
    "colorforpanelborders": "#ffC0C0C0",
    "colorforpanelheaderline": "#ffC0C0C0",
    "colorfortooltipforeground": "#ffF5FCFF",
    "colorforspeedmetertext": "#FF222222",
    "colorforspeedmeteraveragetext": "#FF222222",
    # The speed-meter graph keys stay unset, as in Dark, so JD keeps its green graph.
    "animationenabled": True,
    "paintstatusbartopborder": True,
    "windowopaque": True,
}
d.update(classic)
d.pop("iconsetid", None)  # classic stock icons, never flat
os.makedirs(os.path.dirname(path), exist_ok=True)
# root can rewrite it even if a previous boot locked it read-only
try:
    os.chmod(path, 0o644)
except Exception:
    pass
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] classic JDDefault LAF colors -> %s" % path)
PYEOF
    chmod 444 "${JD_CFG}/laf/JDDefaultLookAndFeel.json" 2>/dev/null || true
    log "classic official JD look (LookAndFeelType.DEFAULT), seeded JDDefaultLookAndFeel.json"
fi

log "done"
exit 0
