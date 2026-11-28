#!/bin/sh
# Build script for mcpack-deobfuscator (requires JDK 8+, works on Linux and macOS).
# Usage: ./build.sh
# Override the compiler with:  JDK8=/path/to/javac ./build.sh

JDK8="${JDK8:-javac}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out"
JAR="$ROOT/mcpack-deobfuscator.jar"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/3] Compiling..."
"$JDK8" -encoding UTF-8 -d "$OUT" \
  "$SRC/com/mcpackdeobfuscator/Lang.java" \
  "$SRC/com/mcpackdeobfuscator/ContentRepairer.java" \
  "$SRC/com/mcpackdeobfuscator/ContentObfuscator.java" \
  "$SRC/com/mcpackdeobfuscator/CheckEngine.java" \
  "$SRC/com/mcpackdeobfuscator/RepairEngine.java" \
  "$SRC/com/mcpackdeobfuscator/ObfuscateEngine.java" \
  "$SRC/com/mcpackdeobfuscator/McpackDeobfuscator.java" || exit 1

echo "[2/3] Copying resources..."
cp "$SRC/messages_English.properties" "$SRC/messages_Chinese.properties" "$OUT/"
cp "$ROOT/version.json" "$OUT/"
cp "$ROOT/LICENSE" "$OUT/"

echo "[3/3] Packaging JAR..."
jar cfe "$JAR" com.mcpackdeobfuscator.McpackDeobfuscator -C "$OUT" . || exit 1

echo
echo "Built: $JAR"
