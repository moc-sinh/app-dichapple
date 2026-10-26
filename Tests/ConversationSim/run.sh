#!/bin/bash
# Runs the Hội thoại checks on Linux, where CI cannot help between pushes:
#   1. Tests/Conversation (as CI does on macOS), with a script-based LanguageDetector;
#   2. a simulated meeting: the real ConversationModel with Soniox, Dịch liền, the audio engine
#      and OpenAI replaced (Stubs.swift), three people talking over each other, the user's turn,
#      echo, GPT failing or slow, a burst of sentences the voice must catch up with.
# Needs a Swift 6.1 toolchain (swift-6.1.2-RELEASE-ubuntu24.04): SWIFT_BIN=/path/to/usr/bin.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
D=$R/Tests/ConversationSim
W=${TMPDIR:-/tmp}/mstrans-sim
mkdir -p "$W"
[ -n "$SWIFT_BIN" ] && export PATH=$SWIFT_BIN:$PATH
# Translator needs FoundationNetworking on Linux.
sed 's/^import Foundation$/import Foundation\n#if canImport(FoundationNetworking)\nimport FoundationNetworking\n#endif/' \
  "$R/Shared/Translator.swift" > "$W/Translator.swift"
C="$R/App/Conversation"

swiftc -O "$R/Shared/Config.swift" "$R/Shared/SharedStore.swift" "$R/Shared/History.swift" "$D/LanguageDetectorStub.swift" \
  "$W/Translator.swift" "$C/PCM.swift" "$C/SpeechSegmenter.swift" "$C/TranscriptFilter.swift" "$C/LiveTranslation.swift" \
  "$C/SonioxTranslation.swift" "$C/SpeechServices.swift" "$C/GoogleVoice.swift" "$C/ConversationCost.swift" \
  "$R/Tests/Conversation/main.swift" -o "$W/conversation-tests" > "$W/tests.log" 2>&1 || { grep error "$W/tests.log"; exit 1; }
"$W/conversation-tests" | tail -3

# The model without UIKit, and Soniox's and OpenAI's sessions replaced by the stubs.
grep -v '^import UIKit$' "$C/ConversationModel.swift" > "$W/ConversationModel.swift"
python3 "$D/cut.py" "$C/SonioxTranslation.swift" "$W/SonioxTranslation.swift" "final class SonioxSession"
python3 "$D/cut.py" "$C/LiveTranslation.swift" "$W/LiveTranslation.swift" "final class LiveSession"
swiftc -O -parse-as-library "$R/Shared/Config.swift" "$R/Shared/SharedStore.swift" "$R/Shared/History.swift" \
  "$D/LanguageDetectorStub.swift" "$W/Translator.swift" "$C/PCM.swift" "$C/SpeechSegmenter.swift" "$C/TranscriptFilter.swift" \
  "$W/LiveTranslation.swift" "$W/SonioxTranslation.swift" "$C/SpeechServices.swift" "$C/GoogleVoice.swift" \
  "$C/ConversationCost.swift" "$C/SpeedLog.swift" "$C/MeetingStore.swift" "$W/ConversationModel.swift" \
  "$D/Stubs.swift" "$D/main.swift" -o "$W/simulation" > "$W/sim.log" 2>&1 || { grep error "$W/sim.log"; exit 1; }
"$W/simulation" | grep -E "FAIL|SIMULATION"
