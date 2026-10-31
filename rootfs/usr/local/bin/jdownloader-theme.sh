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
#   jd-highlighter-dark = the Dark palette, borderless, with one accent colour picked in JD
#   jd-highlighter-light = the same design on a light Carbon palette
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
# The accent is picked in JD's settings, which store it in JD_HL_STORE, and baked into the
# FlatLaf control defaults on every start. Tests pass it as $2 and point
# JD_FLATLAF_DEFAULTS_DIR elsewhere.
HL_STORE="${JD_HL_STORE:-/config/.jd-highlighter}"
ACCENT="${2:-$(cat "${HL_STORE}/accent" 2>/dev/null)}"
ACCENT="${ACCENT:-#ffee00}"
FLATLAF_DEFAULTS_DIR="${JD_FLATLAF_DEFAULTS_DIR:-/opt/JDownloader/flatlaf-defaults}"
HL=0
log() { echo "[jdownloader-theme] $*"; }
# A short or mistyped value would end up as an empty control theme or a wrong colour.
case "${ACCENT#\#}" in
    [0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]) ;;
    *) log "accent ${ACCENT} is not a #rrggbb colour, using #ffee00"; ACCENT="#ffee00" ;;
esac

# accent_fg and render_properties are copied from lib/accent.sh in jd-highlighter;
# keep the two in sync.
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

mkdir -p "${JD_CFG}/laf"

# Unlock what a previous run locked, so switching themes works.
chmod 644 "${JD_CFG}/laf/"*.json 2>/dev/null || true
chmod 644 "${JD_CFG}/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" 2>/dev/null || true

# A JD Highlighter run leaves its rendered defaults in place of the Plain ones, which
# outlive a theme change on a restarted container. The Highlighter branches below render
# theirs again, so the shipped Plain files go back first.
for f in FlatDarkLaf FlatLightLaf; do
    [ -f "${FLATLAF_DEFAULTS_DIR}/plain/$f.properties" ] && \
        cp -f "${FLATLAF_DEFAULTS_DIR}/plain/$f.properties" "${FLATLAF_DEFAULTS_DIR}/$f.properties"
done

# The helper resolves every casing of JDDEFAULT the same way autostart does.
HELPERS="/usr/local/bin/jdownloader-laf-helpers.py"
if [ -f "${HELPERS}" ]; then
    LAF=$(python3 "${HELPERS}" resolve "${THEME}" | awk -F= '/^laf=/{print $2; exit}')
else
    THEME_LC=$(printf '%s' "${THEME}" | tr '[:upper:]' '[:lower:]')
    case "${THEME_LC}" in
        jddefault) LAF="DEFAULT" ;;
        light|jd_plain|jd-highlighter-light) LAF="FLATLAF_LIGHT" ;;
        *dark*) LAF="FLATLAF_DARK" ;;
        *) LAF="FLATLAF_DARK" ;;
    esac
fi
: "${LAF:=FLATLAF_DARK}"
case "${THEME}" in
    jd-highlighter|*[Hh]ighlight*) HL=1 ;;
esac
log "Theme=${THEME} -> lookandfeeltheme=${LAF}"

# JD_ICONS picks color (JD's flat set), mono, mono-light or mono-dark. JD ships no
# monochrome set and FlatLaf cannot tint JD's icons, which load through AppWork's
# NewTheme past the LAF, so the mono modes recolour the flat set on this machine:
# saturated fills become one tone, near-white knockouts turn transparent. The flat
# set is icons8 CC BY-ND, so the copy is never redistributed. mono follows the LAF,
# mono-light and mono-dark stay fixed when the theme changes. jd-highlighter
# defaults to mono, every other theme to color.
if [ "${HL}" = "1" ]; then
    ICONS="${JD_ICONS:-mono}"
else
    ICONS="${JD_ICONS:-color}"
fi
ICONSET="flat"
TONE=""
case "${ICONS,,}" in
    mono|bw|monochrome|blackwhite) TONE="auto"  ;;
    mono-light|monolight)          TONE="light" ;;
    mono-dark|monodark)            TONE="dark"  ;;
esac
if [ -n "${TONE}" ]; then
    if [ "${TONE}" = "auto" ]; then
        TONE="light"; [ "${LAF}" = "FLATLAF_LIGHT" ] && TONE="dark"
    fi
    MONO="#f4f4f4"; [ "${TONE}" = "dark" ] && MONO="#161616"
    [ "${LAF}" = "FLATLAF_DARK" ] && [ "${TONE}" = "dark" ] && \
        log "note: mono-dark on the dark theme -> glyphs only readable on the accent fill"
    # JD renders "flat" directly, ignores a custom iconsetid and re-extracts flat at
    # GUI start, so only recolouring flat in place sticks. The recolour is idempotent
    # and autostart runs it again after launch to catch the late extraction.
    printf '%s' "${MONO}" > /tmp/.jd-mono-hex 2>/dev/null || true
    if [ -d "${JD_DIR}/themes/flat" ]; then
        python3 /usr/local/bin/jd-mono-icons.py "${JD_DIR}/themes/flat" "${MONO}" || log "mono icon recolour failed"
    fi
else
    rm -f /tmp/.jd-mono-hex 2>/dev/null || true
fi
log "icons=${ICONS} -> iconsetid=${ICONSET} (mono tone=${TONE:-none})"

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
    python3 - "${JD_CFG}/laf/FlatDarkLaf.json" "${ICONSET}" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
iconset = sys.argv[2]
# A fresh dict rather than a merge, so values for keys no longer set cannot linger
# (an old grey speed-meter graph, for one). JD fills every omitted key with its own
# default.
d = {
    "iconsetid": iconset,
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
print("[jdownloader-theme] Carbon #161616 colorfor* + iconsetid=%s -> %s" % (iconset, path))
PYEOF

    # jd-highlighter replaces the baked-in control defaults with the rendered
    # template. Dark keeps the file from the image.
    DEF="${FLATLAF_DEFAULTS_DIR}/FlatDarkLaf.properties"
    TMPL="${FLATLAF_DEFAULTS_DIR}/highlighter.properties.tmpl"
    if [ "${HL}" = "1" ] && [ -f "${TMPL}" ]; then
        render_properties "${TMPL}" "${ACCENT}" "${DEF}"
        log "jd-highlighter: wrote accented FlatLaf defaults (accent=${ACCENT}) -> ${DEF}"
    fi
    # jd-highlighter separates JD's content areas by shade instead of lines.
    if [ "${HL}" = "1" ]; then
        ACC_HEX=$(printf '%s' "${ACCENT}" | sed 's/^#//')
        ACC_FG=$(accent_fg "${ACCENT}" | sed 's/^#//')
        python3 - "${JD_CFG}/laf/FlatDarkLaf.json" "${ACC_HEX}" "${ACC_FG}" <<'PYEOF'
import json, sys
p, acc, accfg = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open(p))
d["colorforpanelborders"] = "#ff161616"
d["linktablehorizontalrowlineweight"] = 0
d["paintstatusbartopborder"] = False
# The download and linkgrabber lists sit on the #242424 card surface shared with the
# settings cards and dialogs; the agent adds the margin that lets the card float.
card = "#ff242424"
d["colorfortablepackagerowbackground"] = card
d["colorfortablealternaterowbackground"] = card
d["colorfortablerowgap"] = card
# The overview and the views column draw their title strip in this colour on top of their card.
d["colorforpanelheaderbackground"] = card
# JD paints the bar fill in contrast to the row, dark on the accent hover row. The
# percentage text has one fixed colour, so it needs a grey that reads on both fills.
for _i in range(1, 6):
    d["colorforprogressbarforeground%d" % _i] = "#ff8d8d8d"
d["colorfortablemouseoverrowbackground"] = "#ff" + acc
d["colorfortablemouseoverrowforeground"] = "#ff" + accfg
json.dump(d, open(p, "w"), indent=2)
print("[jdownloader-theme] jd-highlighter: borderless panels, list card, accent row hover")
PYEOF
    fi
    # Keeps JD's unprivileged process from resetting the file (see the header).
    chmod 444 "${JD_CFG}/laf/FlatDarkLaf.json" 2>/dev/null || true
elif [ "${LAF}" = "FLATLAF_LIGHT" ] && [ "${HL}" = "1" ]; then
    # jd-highlighter-light: the dark branch's layout on the light Carbon greys. The
    # lists, overview and views column sit on a white card on the #f4f4f4 base.
    DEF="${FLATLAF_DEFAULTS_DIR}/FlatLightLaf.properties"
    TMPL="${FLATLAF_DEFAULTS_DIR}/highlighter-light.properties.tmpl"
    if [ -f "${TMPL}" ]; then
        render_properties "${TMPL}" "${ACCENT}" "${DEF}"
        log "jd-highlighter-light: wrote accented FlatLaf defaults (accent=${ACCENT}) -> ${DEF}"
    fi
    ACC_HEX=$(printf '%s' "${ACCENT}" | sed 's/^#//')
    ACC_FG=$(accent_fg "${ACCENT}" | sed 's/^#//')
    python3 - "${JD_CFG}/laf/FlatLightLaf.json" "${ICONSET}" "${ACC_HEX}" "${ACC_FG}" <<'PYEOF'
import json, os, sys
path, iconset, acc, accfg = sys.argv[1:5]
card = "#ffffffff"
d = {
    "iconsetid": iconset,
    "colorforpanelbackground":                    "#fff4f4f4",
    "colorforpanelborders":                       "#fff4f4f4",
    "colorforpanelheaderbackground":              card,
    "colorforpanelheaderforeground":              "#ff161616",
    "colorforconfigheadertextcolor":              "#ff161616",
    "colorforconfigpaneldescriptiontext":         "#ff161616",
    "configlabelenabledtextcolor":                "#ff161616",
    "configlabeldisabledtextcolor":               "#ffa8a8a8",
    "colorfortablepackagerowbackground":          card,
    "colorfortablepackagerowforeground":          "#ff161616",
    "colorfortablealternaterowbackground":        card,
    "colorfortablealternaterowforeground":        "#ff161616",
    "colorfortableselectedrowsbackground":        "#ffd0d0d0",
    "colorfortableselectedrowsforeground":        "#ff161616",
    "colorfortablemouseoverrowbackground":        "#ff" + acc,
    "colorfortablemouseoverrowforeground":        "#ff" + accfg,
    "colorfortablerowgap":                        card,
    "colorfortablesortedcolumnview":              "#ffe0e0e0",
    "colorfortablefilteredview":                  "#ff6f6f6f",
    "colorfortooltipforeground":                  "#ff161616",
    # Carbon's light-theme red and amber, a shade deeper than the dark theme's so they read on white.
    "colorforerrorforeground":                    "#ffda1e28",
    "colorforlinkgrabberdupehighlighter":         "#33da1e28",
    "colorfortableaccounterrorrowbackground":     "#40da1e28",
    "colorfortableaccounterrorrowforeground":     "#ff161616",
    "colorfortableaccounttemperrorrowbackground": "#40f1c21b",
    "colorfortableaccounttemperrorrowforeground": "#ff161616",
    "colorforprogressbarforeground1":             "#ff8d8d8d",
    "colorforprogressbarforeground2":             "#ff8d8d8d",
    "colorforprogressbarforeground3":             "#ff8d8d8d",
    "colorforprogressbarforeground4":             "#ff8d8d8d",
    "colorforprogressbarforeground5":             "#ff8d8d8d",
    "colorforspeedmetertext":                     "#ff161616",
    "colorforspeedmeteraveragetext":              "#ff525252",
    "colorforscrollbarsnormalstate":              "#ffc6c6c6",
    "colorforscrollbarsmouseoverstate":           "#ffa8a8a8",
    "tablealternaterowhighlightenabled":          False,
    "textantialiasenabled":                       True,
    "linktablehorizontalrowlineweight":           0,
    "paintstatusbartopborder":                    False,
    "windowdecorationenabled":                    False,
}
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] jd-highlighter-light: light Carbon colorfor* + iconsetid=%s -> %s" % (iconset, path))
PYEOF
    chmod 444 "${JD_CFG}/laf/FlatLightLaf.json" 2>/dev/null || true
elif [ "${LAF}" = "FLATLAF_LIGHT" ]; then
    python3 - "${JD_CFG}/laf/FlatLightLaf.json" "${ICONSET}" <<'PYEOF'
import json, os, sys
path = sys.argv[1]
iconset = sys.argv[2]
# A fresh dict, for the same reason as in the dark branch.
d = {"iconsetid": iconset, "windowdecorationenabled": False}  # no FlatLaf title bar (kiosk)
os.makedirs(os.path.dirname(path), exist_ok=True)
json.dump(d, open(path, "w"), indent=2)
print("[jdownloader-theme] light: iconsetid=%s -> %s" % (iconset, path))
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
