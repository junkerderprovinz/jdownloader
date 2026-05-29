#!/bin/bash
# pre-install-jd.sh — runs in Docker build to install JDownloader 2 inside the
# image so the user's first container start is a copy operation, not a
# bootstrap install with multiple modal dialogs ("Tray isn't supported",
# "FLATLAF_DARK is not installed", etc.).
#
# Flow:
#   1. Pre-seed our configs (LAFSettings.json with iconsetid=flat,
#      GraphicalUserInterfaceSettings.json with FLATLAF_DARK, tray disabled,
#      patched FlatLaf JAR + dep.json with confirm=true).
#   2. Start Xvfb on :99 so JD's Swing GUI has a virtual display.
#   3. Run xdotool dialog-dismisser in the background: any popup that
#      appears gets <Enter> sent within a second.
#   4. Launch JDownloader.jar bootstrap — it downloads JD2, installs
#      extensions (flatlaf-themes, iconset-flat), then renders the main
#      window.
#   5. Wait for libs/extensions/ to contain >=2 JARs (= extensions installed)
#      OR 180s timeout.
#   6. Disable tray extension + kill its JAR (renamed to .disabled).
#   7. Tear down JD + Xvfb cleanly.
#   8. The resulting /tmp/JDownloader/ snapshot is copied to
#      /opt/JDownloader/snapshot/ by the Dockerfile.

set -euo pipefail

SNAP="/tmp/JDownloader"
LAUNCHER="${SNAP}/JDownloader.jar"
DISPLAY_NUM=99
TIMEOUT=300

log() { echo "[pre-install] $*" >&2; }

mkdir -p "${SNAP}/cfg" "${SNAP}/libs/laf"

# ---- 1. Pre-seed our configs ------------------------------------------------
log "seed configs"
python3 /usr/local/bin/seed-flatlaf.py /opt/JDownloader/flatlaf.jar "${SNAP}/libs/laf"
python3 /usr/local/bin/disable-tray.py "${SNAP}/cfg"
# LAFSettings: iconsetid=flat
cat > "${SNAP}/cfg/org.jdownloader.updatev2.gui.LAFSettings.json" <<EOF
{"iconsetid":"flat"}
EOF
# GraphicalUserInterfaceSettings: lookandfeeltheme=FLATLAF_DARK
cat > "${SNAP}/cfg/org.jdownloader.settings.GraphicalUserInterfaceSettings.json" <<EOF
{"lookandfeeltheme":"FLATLAF_DARK","bannerenabled":true,"donatebuttonstate":"AUTO_VISIBLE"}
EOF

# Download bootstrap
log "download bootstrap"
wget -q -O "${LAUNCHER}" "https://installer.jdownloader.org/JDownloader.jar"

# ---- 2. Start Xvfb ----------------------------------------------------------
log "start Xvfb :${DISPLAY_NUM}"
Xvfb ":${DISPLAY_NUM}" -screen 0 1280x800x24 -nolisten tcp &
XVFB_PID=$!
sleep 2
export DISPLAY=":${DISPLAY_NUM}"

cleanup() {
    log "cleanup"
    pkill -f "JDownloader.jar" 2>/dev/null || true
    sleep 2
    pkill -9 -f "JDownloader.jar" 2>/dev/null || true
    kill "${XVFB_PID}" 2>/dev/null || true
    kill "${DISMISS_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# ---- 3. Background dialog dismisser ----------------------------------------
# Polls every 1 second for any visible window.
#   - "JDownloader 2" — the main window; leave alone
#   - "JDownloader Design-Update" — FLATLAF install prompt; click OK so the
#     design is registered as "installed" inside the snapshot
#   - "Error" / anything else — dismiss with Enter
# Order matters: more specific patterns must come BEFORE the catch-all
# "JDownloader"* match (otherwise Design-Update would be skipped as main window).
log "start dialog dismisser"
(
    while true; do
        sleep 1
        for wid in $(xdotool search --onlyvisible "" 2>/dev/null || true); do
            wname=$(xdotool getwindowname "${wid}" 2>/dev/null || echo "")
            case "${wname}" in
                "JDownloader Design-Update"*)
                    # FLATLAF install dialog — accept (OK is default button)
                    xdotool windowactivate "${wid}" 2>/dev/null || true
                    sleep 0.5
                    xdotool key --window "${wid}" Return 2>/dev/null || true
                    log "design-update accepted: ${wname}"
                    sleep 30  # let the install actually complete before next pass
                    ;;
                "JDownloader 2"*)
                    continue
                    ;;
                "")
                    continue
                    ;;
                *)
                    xdotool windowactivate "${wid}" 2>/dev/null || true
                    sleep 0.3
                    xdotool key --window "${wid}" Return 2>/dev/null || true
                    log "dismissed: ${wname}"
                    ;;
            esac
        done
    done
) &
DISMISS_PID=$!

# ---- 4. Launch JD ----------------------------------------------------------
log "launch JDownloader bootstrap"
cd "${SNAP}"
LAF_JAR="${SNAP}/libs/laf/flatlaf.jar"
export JAVA_TOOL_OPTIONS="-Xbootclasspath/a:${LAF_JAR} -Dswing.defaultlaf=com.formdev.flatlaf.FlatDarkLaf"
java -jar "${LAUNCHER}" &
JD_PID=$!

# ---- 5. Wait for installation to complete ----------------------------------
log "wait for extensions to install (timeout ${TIMEOUT}s)"
deadline=$(( $(date +%s) + TIMEOUT ))
done=0
while [ "$(date +%s)" -lt "${deadline}" ]; do
    sleep 5
    # Check if extensions installed
    if [ -d "${SNAP}/libs/extensions" ]; then
        count=$(find "${SNAP}/libs/extensions" -name '*.jar' 2>/dev/null | wc -l)
        if [ "${count}" -ge 1 ]; then
            log "extensions installed (${count} JARs)"
            # Give it 30 more seconds to finish any background work
            sleep 30
            done=1
            break
        fi
    fi
    # Also check if libs/laf was populated by JD beyond what we seeded
    laf_count=$(find "${SNAP}/libs/laf" -name '*.jar' 2>/dev/null | wc -l)
    log "  progress: laf_jars=${laf_count}"
done

if [ "${done}" = "0" ]; then
    log "WARN: timeout reached without confirmed extension install"
fi

# ---- 6. Disable tray + kill tray extension ---------------------------------
log "disable tray + kill tray extension"
python3 /usr/local/bin/disable-tray.py "${SNAP}/cfg"
python3 /usr/local/bin/kill-tray-extension.py "${SNAP}"

# ---- 7. Cleanup ------------------------------------------------------------
log "shutdown JD"
kill -TERM "${JD_PID}" 2>/dev/null || true
sleep 5
kill -KILL "${JD_PID}" 2>/dev/null || true

# Remove session/log noise that would re-appear in user's /config
rm -rf "${SNAP}/tmp" "${SNAP}/logs" "${SNAP}/sessions" "${SNAP}/update" 2>/dev/null || true

log "snapshot ready at ${SNAP}"
ls -la "${SNAP}" || true
exit 0
