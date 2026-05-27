#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# jdownloader-theme.sh <theme-name>
# -----------------------------------------------------------------------------
# Setzt das JDownloader UI-Theme und den passenden Look-and-Feel.
# Themes mit "dark" im Namen (Groß-/Kleinschreibung egal) → FlatDarkLaf.
#
# Beispiele:
#   jdownloader-theme.sh JD_Plain_Dark   → dark (Standard)
#   jdownloader-theme.sh JD_Plain        → light
#   jdownloader-theme.sh JDDEFAULT       → light
# -----------------------------------------------------------------------------
set -e

THEME="${1:-JD_Plain_Dark}"
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

LAF_JSON="${JD_CFG}/org.jdownloader.gui.laf.json"
THEME_JSON="${JD_CFG}/org.jdownloader.gui.theme.ThemeManager.json"

# Dark-Erkennung: alle Theme-Namen mit "dark" (case-insensitive) → FlatDarkLaf
case "${THEME}" in
    *[Dd][Aa][Rr][Kk]*) LAF="com.formdev.flatlaf.FlatDarkLaf" ;;
    *)                   LAF="com.formdev.flatlaf.FlatLightLaf" ;;
esac

log "Setting theme: ${THEME} (LAF: ${LAF})"

seed_json "${LAF_JSON}" "{\"lafClassName\":\"${LAF}\",\"active\":true}"
update_json_key "${LAF_JSON}" "lafClassName" "\"${LAF}\""
update_json_key "${LAF_JSON}" "active" 'true'

seed_json "${THEME_JSON}" "{\"theme\":\"${THEME}\",\"variant\":\"__NONE__\",\"iconSet\":\"DEFAULT\"}"
update_json_key "${THEME_JSON}" "theme" "\"${THEME}\""

log "Theme done (theme=${THEME})"
exit 0
