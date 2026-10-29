#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android library (too large to commit).
set -euo pipefail
cd "$(dirname "$0")/../app"
mkdir -p libs
VER=2.0.1
OUT=libs/sherpa-onnx-$VER.aar
if [ ! -f "$OUT" ]; then
  curl -fSL -o "$OUT" "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
fi
# Only arm64 is packaged; drop the other ABIs to keep the build lean.
zip -q -d "$OUT" 'jni/armeabi-v7a/*' 'jni/x86/*' 'jni/x86_64/*' || true
ls -lh "$OUT"
