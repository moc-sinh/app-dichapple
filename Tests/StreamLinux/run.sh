#!/bin/bash
# The streamed translation end to end on Linux: Translator's Apple-only streaming part compiled
# with a URLSession that answers from a script (Shim.swift). CI on macOS runs Tests/Stream only.
# Needs a Swift 6.1 toolchain: SWIFT_BIN=/path/to/usr/bin.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
W=${TMPDIR:-/tmp}/mstrans-stream
mkdir -p "$W"
[ -n "$SWIFT_BIN" ] && export PATH=$SWIFT_BIN:$PATH
sed 's/^    #if !canImport(FoundationNetworking)$/    #if true/; s/^        #if !canImport(FoundationNetworking)$/        #if true/' \
  "$R/Shared/Translator.swift" > "$W/Translator.swift"
swiftc "$R/Shared/Config.swift" "$R/Shared/SharedStore.swift" "$R/Shared/History.swift" "$W/Translator.swift" \
  "$R/Tests/StreamLinux/Shim.swift" "$R/Tests/StreamLinux/main.swift" -o "$W/stream-e2e" > "$W/build.log" 2>&1 \
  || { grep -E "error" "$W/build.log"; exit 1; }
"$W/stream-e2e"
