#!/bin/sh
# Build script for mcpack-deobfuscator (requires JDK 8+, works on Linux and macOS).
# Usage: ./build.sh
# Override the compiler with:  JDK8=/path/to/javac ./build.sh

JDK8="${JDK8:-javac}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/src"
OUT="$ROOT/out"
JAR="$ROOT/mcpack-deobfuscator.jar"
PKG="$SRC/com/github/_848488489_ff/mcpack_deobfuscator"
MAIN="com.github._848488489_ff.mcpack_deobfuscator.McpackDeobfuscator"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "[1/3] Compiling..."
"$JDK8" -encoding UTF-8 -d "$OUT" "$PKG"/*.java || exit 1

echo "[2/3] Copying resources..."
cp "$SRC/messages_English.properties" "$SRC/messages_Chinese.properties" "$OUT/"
cp "$ROOT/version.json" "$OUT/"
cp "$ROOT/LICENSE" "$OUT/"

echo "[3/3] Packaging JAR..."
jar cfe "$JAR" "$MAIN" -C "$OUT" . || exit 1

echo
echo "Built: $JAR"
