#!/bin/sh
# test-theme-highlighter.sh — temp-dir harness for jdownloader-theme.sh's
# jd-highlighter path. Asserts the Carbon colorfor* JSON, the FLATLAF_DARK LAF
# selection, and the accent-substituted FlatLaf control defaults. Also proves the
# plain JD_THEME=Dark path is unchanged (no accent write). No Docker/JD required.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="${HERE}/jdownloader-theme.sh"
TMPL_SRC="${HERE}/../../../opt/JDownloader/flatlaf-defaults/highlighter.properties.tmpl"

[ -f "${SCRIPT}" ]   || { echo "FAIL: jdownloader-theme.sh not found at ${SCRIPT}"; exit 1; }
[ -f "${TMPL_SRC}" ] || { echo "FAIL: highlighter.properties.tmpl not found at ${TMPL_SRC}"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"' EXIT

# ---------------------------------------------------------------------------
# 1) jd-highlighter path — JD_ACCENT=#ffee00 (light accent -> dark button fg)
# ---------------------------------------------------------------------------
JD1="${WORK}/jd1"
DEF1="${WORK}/def1"
mkdir -p "${JD1}" "${DEF1}"
cp "${TMPL_SRC}" "${DEF1}/highlighter.properties.tmpl"

JD_INST_DIR="${JD1}" \
JD_ACCENT="#ffee00" \
JD_FLATLAF_DEFAULTS_DIR="${DEF1}" \
    sh "${SCRIPT}" jd-highlighter >/dev/null

# colorfor* JSON exists + valid JSON
JSON="${JD1}/cfg/laf/FlatDarkLaf.json"
[ -f "${JSON}" ] || { echo "FAIL: colorfor* JSON not written (${JSON})"; exit 1; }
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "${JSON}" \
    || { echo "FAIL: colorfor* JSON is not valid JSON"; exit 1; }

# lookandfeeltheme == FLATLAF_DARK
GUI="${JD1}/cfg/org.jdownloader.settings.GraphicalUserInterfaceSettings.json"
[ -f "${GUI}" ] || { echo "FAIL: GUI settings not written (${GUI})"; exit 1; }
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('lookandfeeltheme')=='FLATLAF_DARK' else 1)" "${GUI}" \
    || { echo "FAIL: lookandfeeltheme != FLATLAF_DARK"; exit 1; }

# rendered control defaults — accent + auto-contrast fg substituted, no leftover tokens
PROPS="${DEF1}/FlatDarkLaf.properties"
[ -f "${PROPS}" ] || { echo "FAIL: accented FlatDarkLaf.properties not written (${PROPS})"; exit 1; }
grep -q '@accentColor                    = #ffee00' "${PROPS}" \
    || { echo "FAIL: accent not substituted"; exit 1; }
grep -q 'ToggleButton.selectedBackground = #ffee00' "${PROPS}" \
    || { echo "FAIL: toggle accent not substituted"; exit 1; }
grep -q 'Button.default.foreground       = #161616' "${PROPS}" \
    || { echo "FAIL: auto-contrast accent fg not substituted (expected #161616 for light accent)"; exit 1; }
if grep -q '@@' "${PROPS}"; then echo "FAIL: leftover @@ token in rendered .properties"; exit 1; fi

# ---------------------------------------------------------------------------
# 2) dark accent -> light button fg (#f4f4f4)
# ---------------------------------------------------------------------------
JD2="${WORK}/jd2"
DEF2="${WORK}/def2"
mkdir -p "${JD2}" "${DEF2}"
cp "${TMPL_SRC}" "${DEF2}/highlighter.properties.tmpl"
JD_INST_DIR="${JD2}" \
JD_ACCENT="#4589ff" \
JD_FLATLAF_DEFAULTS_DIR="${DEF2}" \
    sh "${SCRIPT}" jd-highlighter >/dev/null
grep -q 'Button.default.foreground       = #f4f4f4' "${DEF2}/FlatDarkLaf.properties" \
    || { echo "FAIL: dark accent should get light fg #f4f4f4"; exit 1; }

# ---------------------------------------------------------------------------
# 3) plain JD_THEME=Dark path unchanged — no accent write, baked defaults intact
# ---------------------------------------------------------------------------
JD3="${WORK}/jd3"
DEF3="${WORK}/def3"
mkdir -p "${JD3}" "${DEF3}"
cp "${TMPL_SRC}" "${DEF3}/highlighter.properties.tmpl"
BAKED="ORIGINAL-BAKED-DARK-DEFAULTS"
printf '%s\n' "${BAKED}" > "${DEF3}/FlatDarkLaf.properties"

JD_INST_DIR="${JD3}" \
JD_ACCENT="#ffee00" \
JD_FLATLAF_DEFAULTS_DIR="${DEF3}" \
    sh "${SCRIPT}" Dark >/dev/null

# LAF still FLATLAF_DARK for plain Dark
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('lookandfeeltheme')=='FLATLAF_DARK' else 1)" \
    "${JD3}/cfg/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" \
    || { echo "FAIL: Dark path lookandfeeltheme != FLATLAF_DARK"; exit 1; }
# the baked FlatDarkLaf.properties must be byte-identical (NOT overwritten with an accent)
if [ "$(cat "${DEF3}/FlatDarkLaf.properties")" != "${BAKED}" ]; then
    echo "FAIL: Dark path overwrote the baked FlatDarkLaf.properties"; exit 1
fi

echo "ALL PASS"
