#!/usr/bin/env python3
"""
Seeds the Breeze-Dark-patched FlatLaf 3.7 JAR into JD's libs/laf/ directory.
Computes SHA256 of the copied JAR and writes a matching dep.json so JD's AWU
registry considers FlatLaf already installed — no install dialog on first start.

Usage: seed-flatlaf.py <src.jar> <dst_lib_dir>
"""
import hashlib, json, shutil, sys
from pathlib import Path


def seed(src: str, lib_dir: str) -> None:
    src_path = Path(src)
    lib_path = Path(lib_dir)
    if not src_path.is_file():
        print(f"[flatlaf-seed] source not found: {src}", flush=True)
        return
    lib_path.mkdir(parents=True, exist_ok=True)
    dst = lib_path / "flatlaf.jar"
    shutil.copy2(src_path, dst)
    sha256 = hashlib.sha256(dst.read_bytes()).hexdigest()
    dep = {
        "artefact": "com.formdev/flatlaf",
        "installed": {
            "date": "2025-12-11T14:11:30.883+01:00",
            "description": "Flat Look and Feel",
            "hashes": {"flatlaf.jar": sha256},
            "licenses": [{"name": "The Apache License, Version 2.0",
                           "url": "https://www.apache.org/licenses/LICENSE-2.0.txt"}],
            "minJRE": "JVM_1_8",
            "name": "FlatLaf",
            "version": "3.7"
        },
        "minJRE": "1.8",
        "provider": "maven",
        "requiredBy": [],
        "autoRenameEnabled": False,
        "confirm": False
    }
    (lib_path / "flatlaf.dep.json").write_text(
        json.dumps(dep, indent=2), encoding="utf-8"
    )
    print(f"[flatlaf-seed] {dst} seeded (hash={sha256[:12]}...)", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"Usage: {sys.argv[0]} <src.jar> <dst_lib_dir>")
    seed(sys.argv[1], sys.argv[2])
