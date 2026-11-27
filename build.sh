#!/bin/sh
# Build script for ZipRepairTool (requires JDK 8+, works on Linux and macOS).
# Usage: ./build.sh
# Override the compiler with:  JDK8=/path/to/javac ./build.sh

JDK8="${JDK8:-javac}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out"
JAR="$ROOT/ZipRepairTool.jar"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/3] Compiling..."
"$JDK8" -encoding UTF-8 -d "$OUT" \
  "$SRC/com/ziptool/Lang.java" \
  "$SRC/com/ziptool/ContentRepairer.java" \
  "$SRC/com/ziptool/ContentObfuscator.java" \
  "$SRC/com/ziptool/CheckEngine.java" \
  "$SRC/com/ziptool/RepairEngine.java" \
  "$SRC/com/ziptool/ProtectEngine.java" \
  "$SRC/com/ziptool/ZipRepairTool.java" || exit 1

echo "[2/3] Copying resources..."
cp "$SRC/messages_English.properties" "$SRC/messages_Chinese.properties" "$OUT/"
cp "$ROOT/version.json" "$OUT/"

echo "[3/3] Packaging JAR..."
jar cfe "$JAR" com.ziptool.ZipRepairTool -C "$OUT" . || exit 1

echo
echo "Built: $JAR"
