#!/usr/bin/env bash
# Points JDownloader's default download folder at the mapped /downloads volume, so a
# fresh install does not write into <JD-home>/downloads inside the appdata dir. JD
# keeps the folder in cfg/org.jdownloader.settings.GeneralSettings.json as
# "defaultdownloadfolder". It is only set while missing, empty or still inside the
# JD install dir; any other value is the user's choice and stays.
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
