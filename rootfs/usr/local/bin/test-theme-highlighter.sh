#!/bin/sh
# Runs jdownloader-theme.sh against temp dirs: the jd-highlighter path has to write the
# Carbon colorfor* JSON, pick FLATLAF_DARK and render the accented control defaults, and
# plain Dark has to leave the baked defaults alone. Needs bash and python3, no Docker.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="${HERE}/jdownloader-theme.sh"
TMPL_SRC="${HERE}/../../../opt/JDownloader/flatlaf-defaults/highlighter.properties.tmpl"

[ -f "${SCRIPT}" ]   || { echo "FAIL: jdownloader-theme.sh not found at ${SCRIPT}"; exit 1; }
[ -f "${TMPL_SRC}" ] || { echo "FAIL: highlighter.properties.tmpl not found at ${TMPL_SRC}"; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"' EXIT

# A light accent gets dark text on it.
JD1="${WORK}/jd1"
DEF1="${WORK}/def1"
mkdir -p "${JD1}" "${DEF1}"
cp "${TMPL_SRC}" "${DEF1}/highlighter.properties.tmpl"

JD_INST_DIR="${JD1}" \
JD_ACCENT="#ffee00" \
JD_FLATLAF_DEFAULTS_DIR="${DEF1}" \
    bash "${SCRIPT}" jd-highlighter >/dev/null

JSON="${JD1}/cfg/laf/FlatDarkLaf.json"
[ -f "${JSON}" ] || { echo "FAIL: colorfor* JSON not written (${JSON})"; exit 1; }
python3 -c "import json,sys; json.load(open(sys.argv[1]))" "${JSON}" \
    || { echo "FAIL: colorfor* JSON is not valid JSON"; exit 1; }
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('colorfortablemouseoverrowbackground')=='#ffffee00' else 1)" "${JSON}" \
    || { echo "FAIL: row hover is not the accent"; exit 1; }

GUI="${JD1}/cfg/org.jdownloader.settings.GraphicalUserInterfaceSettings.json"
[ -f "${GUI}" ] || { echo "FAIL: GUI settings not written (${GUI})"; exit 1; }
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('lookandfeeltheme')=='FLATLAF_DARK' else 1)" "${GUI}" \
    || { echo "FAIL: lookandfeeltheme != FLATLAF_DARK"; exit 1; }

PROPS="${DEF1}/FlatDarkLaf.properties"
[ -f "${PROPS}" ] || { echo "FAIL: accented FlatDarkLaf.properties not written (${PROPS})"; exit 1; }
grep -Eq '^@accentColor +=  ?#ffee00$' "${PROPS}" \
    || { echo "FAIL: accent not substituted"; exit 1; }
grep -Eq '^ToggleButton.selectedBackground +=  ?#ffee00$' "${PROPS}" \
    || { echo "FAIL: toggle accent not substituted"; exit 1; }
grep -Eq '^TabbedPane.selectedForeground +=  ?#161616$' "${PROPS}" \
    || { echo "FAIL: light accent should get dark text #161616"; exit 1; }
if grep -q '@@' "${PROPS}"; then echo "FAIL: leftover @@ token in rendered .properties"; exit 1; fi

# A dark accent gets light text on it.
JD2="${WORK}/jd2"
DEF2="${WORK}/def2"
mkdir -p "${JD2}" "${DEF2}"
cp "${TMPL_SRC}" "${DEF2}/highlighter.properties.tmpl"
JD_INST_DIR="${JD2}" \
JD_ACCENT="#4589ff" \
JD_FLATLAF_DEFAULTS_DIR="${DEF2}" \
    bash "${SCRIPT}" jd-highlighter >/dev/null
grep -Eq '^TabbedPane.selectedForeground +=  ?#f4f4f4$' "${DEF2}/FlatDarkLaf.properties" \
    || { echo "FAIL: dark accent should get light text #f4f4f4"; exit 1; }

# Plain Dark keeps the baked defaults byte for byte.
JD3="${WORK}/jd3"
DEF3="${WORK}/def3"
mkdir -p "${JD3}" "${DEF3}"
cp "${TMPL_SRC}" "${DEF3}/highlighter.properties.tmpl"
BAKED="ORIGINAL-BAKED-DARK-DEFAULTS"
printf '%s\n' "${BAKED}" > "${DEF3}/FlatDarkLaf.properties"

JD_INST_DIR="${JD3}" \
JD_ACCENT="#ffee00" \
JD_FLATLAF_DEFAULTS_DIR="${DEF3}" \
    bash "${SCRIPT}" Dark >/dev/null

python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('lookandfeeltheme')=='FLATLAF_DARK' else 1)" \
    "${JD3}/cfg/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" \
    || { echo "FAIL: Dark path lookandfeeltheme != FLATLAF_DARK"; exit 1; }
if [ "$(cat "${DEF3}/FlatDarkLaf.properties")" != "${BAKED}" ]; then
    echo "FAIL: Dark path overwrote the baked FlatDarkLaf.properties"; exit 1
fi
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get('colorfortablemouseoverrowbackground')=='#ff0b0b0b' else 1)" \
    "${JD3}/cfg/laf/FlatDarkLaf.json" \
    || { echo "FAIL: Dark path row hover changed"; exit 1; }

echo "ALL PASS"
