#!/usr/bin/env bash
# Builds libcuenote_ml for this computer (Linux or macOS) so the JVM tests and
# the laptop pipeline runner can use the real whisper.cpp and Silero models.
# Needs cmake, a C++ compiler and a JDK. Output: core/ml/build/host/.
#
#   core/ml/host-build.sh             # fetches whisper.cpp
#   WHISPER_SOURCE_DIR=~/whisper.cpp core/ml/host-build.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build/host"
extra=()
if [[ -n "${WHISPER_SOURCE_DIR:-}" ]]; then extra+=("-DWHISPER_SOURCE_DIR=$WHISPER_SOURCE_DIR"); fi
cmake -S "$here/src/main/cpp" -B "$out/cmake" -DCMAKE_BUILD_TYPE=Release "${extra[@]}"
cmake --build "$out/cmake" --target cuenote_ml -j
cp "$out"/cmake/libcuenote_ml.* "$out/"
echo "Built $(ls "$out"/libcuenote_ml.*)"
