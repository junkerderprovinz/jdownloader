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
# Always overwrites — env var wins over anything JD wrote on the previous run.

THEME="${1:-Dark}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"
# JD Highlighter: the accent (any hex, default electric yellow) baked into the FlatLaf
# control defaults on start. Overridable for tests via JD_FLATLAF_DEFAULTS_DIR.
ACCENT="${JD_ACCENT:-#ffee00}"
FLATLAF_DEFAULTS_DIR="${JD_FLATLAF_DEFAULTS_DIR:-/opt/JDownloader/flatlaf-defaults}"
HL=0
log() { echo "[jdownloader-theme] $*"; }

# --- vendored from jd-highlighter lib/accent.sh (no jd-highlighter checkout at runtime) ---
# accent_fg <hex> -> #161616 for a light accent, #f4f4f4 for a dark accent (WCAG luminance).
accent_fg() {
  hex=$(printf '%s' "$1" | tr 'A-F' 'a-f' | sed 's/^#//')
  r=$(printf '%d' "0x$(echo "$hex" | cut -c1-2)"); g=$(printf '%d' "0x$(echo "$hex" | cut -c3-4)"); b=$(printf '%d' "0x$(echo "$hex" | cut -c5-6)")
  fg=$(awk -v r="$r" -v g="$g" -v b="$b" \
    'function lin(c){c=c/255; return (c<=0.03928)?c/12.92:((c+0.055)/1.055)^2.4}
     BEGIN{L=0.2126*lin(r)+0.7152*lin(g)+0.0722*lin(b); print (L>=0.5)?"#161616":"#f4f4f4"}')
  echo "$fg"
}
# render_properties <tmpl> <accent-hex> <out> -> writes tmpl with @@ACCENT@@/@@ACCENT_FG@@ filled.
render_properties() {
  tmpl="$1"; accent="$2"; out="$3"
  case "$accent" in \#*) : ;; *) accent="#$accent" ;; esac
  fg=$(accent_fg "$accent")
  sed -e "s/@@ACCENT@@/$accent/g" -e "s/@@ACCENT_FG@@/$fg/g" "$tmpl" > "$out"
}
# --- end vendored ---

mkdir -p "${JD_CFG}/laf"

case "${THEME}" in
    jd-highlighter|*[Hh]ighlight*)          LAF="FLATLAF_DARK"  ; HL=1 ;;
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

    # 3) JD Highlighter: bake the user's accent into the FlatLaf control defaults
    #    (borderless / rounded / accented controls). Only for JD_THEME=jd-highlighter
    #    (HL=1); plain Dark/Light leave the baked-in FlatDarkLaf.properties untouched.
    DEF="${FLATLAF_DEFAULTS_DIR}/FlatDarkLaf.properties"
    TMPL="${FLATLAF_DEFAULTS_DIR}/highlighter.properties.tmpl"
    if [ "${HL}" = "1" ] && [ -f "${TMPL}" ]; then
        render_properties "${TMPL}" "${ACCENT}" "${DEF}"
        log "jd-highlighter: wrote accented FlatLaf defaults (accent=${ACCENT}) -> ${DEF}"
    fi
else
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
fi

log "done"
exit 0
