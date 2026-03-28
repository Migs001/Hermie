#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# setup_llama.sh — Clone llama.cpp into the project for NDK build
#
# Run once (or after updating the pinned tag) from the project root:
#   bash setup_llama.sh
#
# The CMake build (triggered by Gradle) will compile the .so
# automatically — this script just fetches the source.
# ─────────────────────────────────────────────────────────────

set -euo pipefail

# Pin to a known-good release that supports Qwen 3.5
LLAMA_CPP_TAG="b8514"   # bump this when you want a newer llama.cpp

DEST="app/src/main/cpp/llama.cpp"

if [ -d "$DEST" ]; then
    echo "llama.cpp already cloned at $DEST"
    echo "To update: rm -rf $DEST && re-run this script"
    exit 0
fi

echo "Cloning llama.cpp (tag $LLAMA_CPP_TAG) …"
git clone --depth 1 --branch "$LLAMA_CPP_TAG" \
    https://github.com/ggml-org/llama.cpp.git "$DEST"

# Remove stuff we don't need to keep the tree small
rm -rf "$DEST/.git" \
       "$DEST/examples" \
       "$DEST/tests" \
       "$DEST/models" \
       "$DEST/docs" \
       "$DEST/scripts" \
       "$DEST/grammars" \
       "$DEST/.github" \
       "$DEST/Makefile" \
       "$DEST/CMakePresets.json"

echo "Done — llama.cpp source is at $DEST"
echo ""
echo "Next steps:"
echo "  1. Install NDK + CMake in Android Studio → SDK Manager → SDK Tools"
echo "  2. Build the project normally — Gradle/CMake will compile the native libs"
