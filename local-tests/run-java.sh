#!/usr/bin/env bash
# Local-only Java/ASM checks (gitignored). Requires JDK under local-tests-out/jdk.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/local-tests-out"
ASM="$OUT/asm.jar"
JAVA_HOME="$(echo "$OUT"/jdk/jdk-* | awk '{print $1}')"
if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
  echo "No JDK in local-tests-out/jdk — skip Java tests" >&2
  exit 0
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$OUT/classes"
javac -cp "$ASM" -d "$OUT/classes" "$ROOT/local-tests/java/GetDisabledIconPatchTest.java"
java -cp "$OUT/classes:$ASM" GetDisabledIconPatchTest
