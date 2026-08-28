#!/usr/bin/env bash
# Fetch the on-device STT assets the build needs but the repo does not track
# (both gitignored — binaries stay out of git history):
#
#   app/libs/sherpa-onnx-1.13.5.aar   (~47 MB, sherpa-onnx Kotlin API + JNI libs)
#   app/src/main/assets/stt/          (~71 MB, Russian zipformer int8 model:
#                                      encoder.onnx, decoder.onnx, joiner.onnx,
#                                      tokens.txt)
#
# See docs/D14_CHAT_UX_AND_LOCAL_STT.md for why these are bundled locally.
set -euo pipefail
cd "$(dirname "$0")/.."

AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar"
AAR_DEST="app/libs/sherpa-onnx-1.13.5.aar"

if [ ! -f "$AAR_DEST" ]; then
  mkdir -p app/libs
  echo "Downloading sherpa-onnx AAR…"
  curl -L --retry 3 -o "$AAR_DEST" "$AAR_URL"
fi

MODEL_DIR="app/src/main/assets/stt"
if [ ! -f "$MODEL_DIR/encoder.onnx" ]; then
  mkdir -p "$MODEL_DIR"
  SRC="../vynkor-plugins/models/stt/zipformer-ru-int8"
  if [ -d "$SRC" ]; then
    echo "Copying model from $SRC …"
    cp "$SRC"/encoder.onnx "$SRC"/decoder.onnx "$SRC"/joiner.onnx "$SRC"/tokens.txt "$MODEL_DIR"/
  else
    echo "ERROR: no local model at $SRC." >&2
    echo "Obtain a sherpa-onnx Russian transducer model and place these files in $MODEL_DIR:" >&2
    echo "  encoder.onnx  decoder.onnx  joiner.onnx  tokens.txt" >&2
    echo "Official alternative (f32, larger):" >&2
    echo "  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-small-zipformer-ru-2024-09-18.tar.bz2" >&2
    exit 1
  fi
fi

echo "STT assets ready."
