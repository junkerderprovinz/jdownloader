#!/usr/bin/env bash
# Prints the screen size the X server should start with, or nothing when neither
# variable is set so the base image's default applies. Shared byte for byte by
# every junkerderprovinz image on linuxserver/baseimage-selkies: fix it in one,
# copy it to the others.
#
# Unraid renders a template variable as a plain <select> as soon as its Default
# contains a "|" (CreateDocker.php, addConfig()), so the template offers MAX_RES
# as a preset dropdown and MAX_RES_CUSTOM as a free field. A usable
# MAX_RES_CUSTOM wins, otherwise the dropdown value applies.
#
# A malformed value is reported and ignored: Xvfb takes the size as a literal
# argument, so a typo like "1920*1080" would leave the container with no
# desktop at all.
set -u

log() { echo "[selkies-resolution] $*" >&2; }

normalise() {
    # An Unraid dropdown hands over its whole label, such as
    # "3840x2160 (4K, ~93 MB)", and people type " 1920 X 1080 ".
    printf '%s' "${1%%(*}" | tr -d '[:space:]' | tr 'X' 'x'
}

# Catches a digit slip, not a size limit: "15360x86400" is well formed but asks
# for a 5 GB framebuffer and gets the container OOM-killed on every boot. 16384
# is the usual ceiling of X drivers and GL, above the base image's default.
SELKIES_MAX_EDGE=16384

valid() {
    [[ "$1" =~ ^([1-9][0-9]{2,4})x([1-9][0-9]{2,4})$ ]] || return 1
    [ "${BASH_REMATCH[1]}" -le "${SELKIES_MAX_EDGE}" ] && [ "${BASH_REMATCH[2]}" -le "${SELKIES_MAX_EDGE}" ]
}

custom="$(normalise "${MAX_RES_CUSTOM:-}")"
preset="$(normalise "${MAX_RES:-}")"

if [ -n "${custom}" ]; then
    if valid "${custom}"; then
        log "using the custom screen size ${custom}"
        printf '%s' "${custom}"
        exit 0
    fi
    log "WARNING: ignoring MAX_RES_CUSTOM='${MAX_RES_CUSTOM:-}'. Expected WIDTHxHEIGHT like 3440x1440, with each side at most ${SELKIES_MAX_EDGE}. Falling back to ${preset:-the image default}."
fi

if [ -n "${preset}" ]; then
    if valid "${preset}"; then
        printf '%s' "${preset}"
        exit 0
    fi
    log "WARNING: ignoring MAX_RES='${MAX_RES:-}'. Expected WIDTHxHEIGHT, with each side at most ${SELKIES_MAX_EDGE}. Falling back to the image default."
fi

exit 0
