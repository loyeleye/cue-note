#!/usr/bin/env bash
# Builds libcuenote_llm (llama.cpp) for this computer, so the laptop vetting
# (`vet ... --gguf`) runs the same native code as the phone. Needs cmake and a
# C++ compiler. Output: core/llm-android/build/host/.
#
#   core/llm-android/host-build.sh
#   LLAMA_SOURCE_DIR=~/llama.cpp core/llm-android/host-build.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build/host"
extra=()
if [[ -n "${LLAMA_SOURCE_DIR:-}" ]]; then extra+=("-DLLAMA_SOURCE_DIR=$LLAMA_SOURCE_DIR"); fi
cmake -S "$here/src/main/cpp" -B "$out/cmake" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON "${extra[@]}"
cmake --build "$out/cmake" --target cuenote_llm -j
cp "$out"/cmake/libcuenote_llm.* "$out/"
echo "Built $(ls "$out"/libcuenote_llm.*)"
