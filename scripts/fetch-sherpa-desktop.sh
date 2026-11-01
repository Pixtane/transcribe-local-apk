#!/usr/bin/env bash
# Prepares the desktop build: the sherpa-onnx Kotlin classes (taken from the Android AAR) and
# the native JNI libraries for the target OS (windows-x64 by default, or linux-x64).
set -euo pipefail
VER=2.0.1
OS=${1:-windows}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
mkdir -p "$ROOT/desktop/libs"
AAR="$ROOT/app/libs/sherpa-onnx-$VER.aar"
if [ ! -f "$AAR" ]; then
  AAR="$TMP/sherpa.aar"
  curl -fSL -o "$AAR" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
fi
PY="$(command -v python3 || command -v python)"
"$PY" -c "import sys, zipfile; open(sys.argv[2], 'wb').write(zipfile.ZipFile(sys.argv[1]).read('classes.jar'))" \
  "$AAR" "$ROOT/desktop/libs/sherpa-onnx-classes.jar"

DEST="$ROOT/desktop/native/$OS-x64"
mkdir -p "$DEST"
curl -fSL -o "$TMP/jni.tar.bz2" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-v$VER-$OS-x64-jni.tar.bz2"
"$PY" -c "import sys, tarfile; tarfile.open(sys.argv[1], 'r:bz2').extractall(sys.argv[2])" "$TMP/jni.tar.bz2" "$TMP"
LIB="$TMP/sherpa-onnx-v$VER-$OS-x64-jni/lib"
if [ "$OS" = windows ]; then
  cp "$LIB/onnxruntime.dll" "$LIB/onnxruntime_providers_shared.dll" "$LIB/sherpa-onnx-jni.dll" "$DEST/"
else
  cp "$LIB/libonnxruntime.so" "$LIB/libsherpa-onnx-jni.so" "$DEST/"
fi
rm -rf "$TMP"
ls -l "$ROOT/desktop/libs" "$DEST"
