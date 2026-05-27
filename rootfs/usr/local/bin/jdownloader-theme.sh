#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# jdownloader-theme.sh <true|false>
# -----------------------------------------------------------------------------
# Aktiviert oder deaktiviert Dark Mode in JDownloader 2.
# JDownloader speichert das Theme in mehreren Config-Dateien unter cfg/.
# -----------------------------------------------------------------------------
set -e

DARK="${1:-true}"
JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"

log() { echo "[jdownloader-theme] $*"; }

mkdir -p "${JD_CFG}"

update_json_key() {
    local file="$1" key="$2" value="$3"
    [[ ! -f "$file" ]] && return 0
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import json
try:
    with open('$file') as f:
        data = json.load(f)
    data['$key'] = $value
    with open('$file', 'w') as f:
        json.dump(data, f, indent=2)
    print('[jdownloader-theme] Updated $key in $file')
except Exception as e:
    print(f'[jdownloader-theme] Skipped $file: {e}')
" 2>/dev/null || true
    fi
}

seed_json() {
    local file="$1" content="$2"
    if [[ ! -f "$file" ]]; then
        echo "$content" > "$file"
        log "Seeded $file"
    fi
}

THEME_JSON="${JD_CFG}/org.jdownloader.gui.theme.ThemeManager.json"

if [[ "${DARK}" == "true" ]]; then
    log "Enabling Dark Mode (JD_Plain + FlatDarkLaf)"

    # Look-and-Feel: FlatDarkLaf als Window-Chrome
    LAF_JSON="${JD_CFG}/org.jdownloader.gui.laf.json"
    seed_json "${LAF_JSON}" '{"lafClassName":"com.formdev.flatlaf.FlatDarkLaf","active":true}'
    update_json_key "${LAF_JSON}" "lafClassName" '"com.formdev.flatlaf.FlatDarkLaf"'
    update_json_key "${LAF_JSON}" "active" 'true'

    # Icon-Theme: JD_Plain — schöne flache Icons, immer erzwingen
    seed_json "${THEME_JSON}" '{"theme":"JD_Plain","variant":"__NONE__","iconSet":"DEFAULT"}'
    update_json_key "${THEME_JSON}" "theme" '"JD_Plain"'

else
    log "Disabling Dark Mode (JD_Plain + FlatLightLaf)"

    # Look-and-Feel: FlatLightLaf
    LAF_JSON="${JD_CFG}/org.jdownloader.gui.laf.json"
    seed_json "${LAF_JSON}" '{"lafClassName":"com.formdev.flatlaf.FlatLightLaf","active":true}'
    update_json_key "${LAF_JSON}" "lafClassName" '"com.formdev.flatlaf.FlatLightLaf"'

    # Icon-Theme: JD_Plain auch im Light Mode
    seed_json "${THEME_JSON}" '{"theme":"JD_Plain","variant":"__NONE__","iconSet":"DEFAULT"}'
    update_json_key "${THEME_JSON}" "theme" '"JD_Plain"'
fi

log "Theme done (dark=${DARK})"
exit 0
