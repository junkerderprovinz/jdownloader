#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# jdownloader-downloaddir.sh
# -----------------------------------------------------------------------------
# Seeds JDownloader's default download folder so a FRESH install writes to the
# mapped /downloads volume (the user's download share) instead of JDownloader's
# built-in default <JD-home>/downloads (= /config/JDownloader/downloads, inside
# the install/appdata dir).
#
# Called by 10-jdownloader-setup BEFORE JDownloader starts.
#
# JDownloader stores the global default download folder in:
#   cfg/org.jdownloader.settings.GeneralSettings.json  ->  "defaultdownloadfolder"
#
# Seed policy (so the folder stays freely changeable in the GUI):
#   - set to ${JD_DOWNLOAD_DIR:-/downloads} ONLY when the value is missing/empty
#     or still points inside the JD install dir (JD's built-in default).
#   - any other value (a real user choice, e.g. /downloads/Movies or /mnt/...) is
#     kept untouched.
# -----------------------------------------------------------------------------
set -e

JD_DIR="${JD_INST_DIR:-/config/JDownloader}"
JD_CFG="${JD_DIR}/cfg"
TARGET="${JD_DOWNLOAD_DIR:-/downloads}"
GENERAL="${JD_CFG}/org.jdownloader.settings.GeneralSettings.json"

log() { echo "[jdownloader-downloaddir] $*"; }

mkdir -p "${JD_CFG}"

python3 - "${GENERAL}" "${TARGET}" "${JD_DIR}" <<'PY' || log "seed failed (non-fatal)"
import json, os, sys

path, target, jd_dir = sys.argv[1], sys.argv[2], sys.argv[3]

data = {}
if os.path.exists(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print("[jdownloader-downloaddir] unreadable JSON, reseeding: %s" % e)
        data = {}

cur = data.get("defaultdownloadfolder")

# Seed only when unset/empty or still inside the JD install dir (JD's built-in
# default). A real user choice elsewhere is preserved.
if (not cur) or cur == jd_dir or cur.startswith(jd_dir + "/"):
    data["defaultdownloadfolder"] = target
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, path)
    print("[jdownloader-downloaddir] defaultdownloadfolder -> %s" % target)
else:
    print("[jdownloader-downloaddir] keeping existing value: %s" % cur)
PY

exit 0
