#!/usr/bin/env bash
# jdownloader-theme.sh <theme>
# Maps JD_THEME to JD's lookAndFeelTheme AND writes JD's native per-LAF colour
# config (cfg/laf/<LAF>.json). JD's ExtTable / panels / settings read these
# "colorfor*" keys themselves — that is why the content areas go dark WITHOUT
# the old JVM agent (same mechanism the community "Material Darker" theme uses).
#
# Standalone desktop port of this palette (same Carbon colours, minus the kiosk-only
# windowdecorationenabled=false): https://github.com/junkerderprovinz/jd-plain-dark - keep in sync.
#
#   Dark  = JD_Plain (flat) icons + IBM Carbon #161616 monochrome "colorfor*" colours
#   Light = JD_Plain (flat) icons + JD's default light colours
#
# Always overwrites — env var wins over anything JD wrote on the previous run. The
# written laf/*.json is then locked read-only (chmod 444, same pattern as the tray
# extension cfg in autostart): JD's own bootstrap installer/first-run setup can reset
# this exact file with its stock (light) colours during its OWN startup, which lands
# AFTER this script already ran — a plain rewrite-before-launch loses that race on some
# boots (reported: content areas stay JD's default light even with JD_THEME=Dark, a
# fresh reinstall included — jdownloader#16). This script itself still runs as root
# (root bypasses the 444), so the next boot's rewrite is unaffected; only JD's JVM,
# which runs as the unprivileged PUID-mapped user, is blocked from overwriting it.

THEME="${1:-Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"
log() { echo "[jdownloader-theme] $*"; }
mkdir -p "${JD_CFG}/laf"

# Canonical value is JDDEFAULT; any casing (jddefault, JdDefault, …) must resolve the
# same — split-brain with lowercase-only classic paths left Metal. Shared with autostart.
HELPERS="/usr/local/bin/jdownloader-laf-helpers.py"
if [ -f "${HELPERS}" ]; then
    LAF=$(python3 "${HELPERS}" resolve "${THEME}" | awk -F= '/^laf=/{print $2; exit}')
else
    # Fallback if helpers missing (should not happen in the image).
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

# 1) Look-and-Feel (window chrome / Swing) — GraphicalUserInterfaceSettings
python3 - "${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" "${LAF}" <<'PYEOF'
import json, os, sys
path, laf = sys.argv[1], sys.argv[2]
d = {}
if os.path.exists(path):
    try: d = json.load(open(path))
    except Exception: pass
d["lookandfeeltheme"] = laf
# Classic DEFAULT must not keep a leftover flat icon set from a previous Dark/Light run.
if laf == "DEFAULT":
    d.pop("iconsetid", None)
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
# Write a FRESH dict (do NOT load+merge the existing file). Otherwise JD's previous
# values for keys we no longer set linger forever (e.g. an old grey speed-meter graph).
# Any key we omit is filled by JD's own default (e.g. the GREEN speed-meter graph).
d = {
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
    "colorfortablealternaterowbackground":        "#ff161616",  # = base: uniform rows, no stripes
    "colorfortablealternaterowforeground":        "#fff4f4f4",
    "colorfortableselectedrowsbackground":        "#ff525252",
    "colorfortableselectedrowsforeground":        "#fff4f4f4",
    "colorfortablemouseoverrowbackground":        "#ff0b0b0b",  # hover = darker than the base (clean, Material-Darker style)
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
    # Account-Manager "Downloadtraffic übrig" bar: a JD legacy (Synthetica) JProgressBar whose
    # TEXT is hard-coded white (no theme key for it — confirmed against Material Darker, which
    # uses a saturated fill for exactly this reason). Leaving these UNSET routed it through
    # FlatLaf, which filled it LIGHT (@accentBaseColor) while the text stayed white → white-on-
    # light, unreadable, and it flickered dark→light on tab open. So set the Synthetica fill to
    # a fixed mid grey: white text stays readable AND the fill is constant (no flicker). As
    # light as possible without the white text vanishing. The FlatLaf download/progress bars do
    # NOT read these keys, so they keep their light fill + dark % text.
    "colorforprogressbarforeground1":             "#ff606060",
    "colorforprogressbarforeground2":             "#ff666666",
    "colorforprogressbarforeground3":             "#ff6c6c6c",
    "colorforprogressbarforeground4":             "#ff666666",
    "colorforprogressbarforeground5":             "#ff606060",
    # speed meter (top-right) — keep JD's GREEN graph (omit current/average/limiter keys
    # = JD defaults); only force the TEXT light so it is readable on the dark panel.
    "colorforspeedmetertext":                     "#fff4f4f4",
    "colorforspeedmeteraveragetext":              "#ffb0b0b0",
    # scrollbars
    "colorforscrollbarsnormalstate":              "#ff393939",
    "colorforscrollbarsmouseoverstate":           "#ff525252",
    # toggles
    "tablealternaterowhighlightenabled":          False,  # uniform rows (no alternating stripes)
    "textantialiasenabled":                       True,
    # No FlatLaf-drawn window title bar: openbox already runs JD's window
    # undecorated + maximised (kiosk). Without this, FlatLaf paints its own
    # title bar back inside the undecorated frame.
    "windowdecorationenabled":                    False,
}
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] Carbon #161616 colorfor* + iconsetid=flat -> %s" % path)
PYEOF
    # See the file-header note: block JD's own (unprivileged) process from
    # resetting this file after we just wrote it; this script runs as root on
    # its next invocation regardless, so re-applying the theme still works.
    chmod 444 "${JD_CFG}/laf/FlatDarkLaf.json" 2>/dev/null || true
elif [ "${LAF}" = "FLATLAF_LIGHT" ]; then
    # Light: JD_Plain (flat) icons, JD's default light colours.
    python3 - "${JD_CFG}/laf/FlatLightLaf.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
# Fresh dict (no load+merge) — same reasoning as the dark branch.
d = {"iconsetid": "flat", "windowdecorationenabled": False}  # no FlatLaf title bar (kiosk)
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] light: iconsetid=flat -> %s" % path)
PYEOF
    chmod 444 "${JD_CFG}/laf/FlatLightLaf.json" 2>/dev/null || true
else
    # JDDEFAULT / LookAndFeelType.DEFAULT — classic Synthetica; seed Vinylwalk3r-style
    # light palette so LAFOptions progress/text colors are never null (NPE in
    # CustomProgressbarPainter + gray dialog text). No flat icon set.
    python3 - "${JD_CFG}/laf/JDDefaultLookAndFeel.json" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
# Prefer overwrite with classic defaults, but merge so progress colors never null
# if a partial file already exists.
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
    # Prefer #aRGB like FlatDark / LAFSettings docs (#ffFF0000) so HexColorString accepts them.
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
    # Speed-meter GRAPH colours intentionally unset — same as upstream Dark: omit
    # current/average/limiter keys so JD keeps its stock green graph.
    "animationenabled": True,
    "paintstatusbartopborder": True,
    "windowopaque": True,
}
d.update(classic)
d.pop("iconsetid", None)  # classic stock icons, never flat
os.makedirs(os.path.dirname(path), exist_ok=True)
# root can rewrite even if previous boot locked the file read-only
try:
    os.chmod(path, 0o644)
except Exception:
    pass
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] classic JDDefault LAF colors -> %s" % path)
PYEOF
    chmod 444 "${JD_CFG}/laf/JDDefaultLookAndFeel.json" 2>/dev/null || true
    log "classic official JD look (LookAndFeelType.DEFAULT) — seeded JDDefaultLookAndFeel.json"
fi

log "done"
exit 0
