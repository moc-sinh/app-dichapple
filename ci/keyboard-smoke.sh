#!/bin/bash
# Opens the MSTrans keyboard in an iOS Simulator and reports whether it starts:
# which keyboard the system shows, whether the extension process stays alive, its
# memory, crash reports and launch errors. Phase 1 is a fresh install; phase 2 adds
# Full Access, an API key and a copied message, as on a set-up iPhone.
# Run from the repo root after building MSTrans for the simulator.
set -uo pipefail

APP=${1:-build/sim/Build/Products/Release-iphonesimulator/MSTrans.app}
OUT=build/smoke
HOST=build/KeyboardHost.app
APP_ID=com.mocsinh.dichapple
KEYBOARD_ID=com.mocsinh.dichapple.keyboard
HOST_ID=com.mocsinh.keyboardhost
REPORTS=~/Library/Logs/DiagnosticReports
FAILED=0
mkdir -p "$OUT"

group() { echo "::group::$*"; }
endgroup() { echo "::endgroup::"; }
crash_reports() { ls "$REPORTS" 2>/dev/null | grep -i -E 'MSTrans' || true; }
keyboard_pid() { pgrep -f 'MSTransKeyboard.appex/MSTransKeyboard' | head -1; }
# Runs a command for at most $1 seconds; macOS has no `timeout`.
limit() { perl -e 'alarm shift @ARGV; exec @ARGV or die "$!"' "$@"; }

group "Host app"
rm -rf "$HOST" && mkdir -p "$HOST"
xcrun -sdk iphonesimulator swiftc -O -target arm64-apple-ios17.0-simulator -module-name Host \
    ci/KeyboardHost/main.swift -o "$HOST/Host" || exit 1
cat > "$HOST/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleIdentifier</key><string>$HOST_ID</string>
    <key>CFBundleExecutable</key><string>Host</string>
    <key>CFBundleName</key><string>Host</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
    <key>LSRequiresIPhoneOS</key><true/>
    <key>MinimumOSVersion</key><string>17.0</string>
    <key>UIDeviceFamily</key><array><integer>1</integer></array>
    <key>UILaunchScreen</key><dict/>
</dict>
</plist>
EOF
codesign -f -s - "$HOST" || exit 1
endgroup

group "App signature and entitlements"
codesign -dv --entitlements - "$APP/PlugIns/MSTransKeyboard.appex" 2>&1 | tail -20
plutil -p "$APP/PlugIns/MSTransKeyboard.appex/Info.plist"
endgroup

group "Simulator"
xcrun simctl list runtimes
RUNTIME=$(xcrun simctl list runtimes -j | python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r.get("platform") == "iOS" and r.get("isAvailable")]
rs.sort(key=lambda r: [int(x) for x in r["version"].split(".")])
print(rs[-1]["identifier"] if rs else "")')
UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"].get(sys.argv[1], [])
phones = [d for d in devices if d["name"].startswith("iPhone")]
print(phones[0]["udid"] if phones else "")' "$RUNTIME")
[ -n "$UDID" ] || { echo "No iPhone simulator for $RUNTIME"; exit 1; }
echo "Runtime $RUNTIME, device $UDID"
DATA=~/Library/Developer/CoreSimulator/Devices/$UDID/data
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false
limit 180 xcrun simctl boot "$UDID"
limit 300 xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl install "$UDID" "$APP" || exit 1
xcrun simctl install "$UDID" "$HOST" || exit 1
# MSTrans first, so it is the keyboard that comes up.
xcrun simctl spawn "$UDID" defaults write -g AppleKeyboards -array "$KEYBOARD_ID" "en_US@sw=QWERTY;hw=Automatic" "emoji@sw=Emoji"
xcrun simctl spawn "$UDID" defaults write -g AppleKeyboardsExpanded -bool true
xcrun simctl spawn "$UDID" defaults write com.apple.Preferences KeyboardLastUsed "$KEYBOARD_ID"
xcrun simctl spawn "$UDID" defaults write com.apple.Preferences KeyboardLastChosen "$KEYBOARD_ID"
xcrun simctl spawn "$UDID" defaults read -g AppleKeyboards
endgroup

# Focuses a text field in the host app and checks the keyboard that comes up.
run_phase() {
    local name=$1 before after new pid
    group "Phase: $name"
    before=$(crash_reports)
    xcrun simctl terminate "$UDID" "$HOST_ID" >/dev/null 2>&1
    pkill -f 'MSTransKeyboard.appex/MSTransKeyboard' >/dev/null 2>&1
    sleep 1
    limit 60 xcrun simctl launch --terminate-running-process \
        --stdout="$PWD/$OUT/$name-host.log" --stderr="$PWD/$OUT/$name-host.err" "$UDID" "$HOST_ID"
    sleep 8
    pid=$(keyboard_pid)
    if [ -n "$pid" ]; then
        echo "Keyboard process: running (pid $pid)"
        limit 20 footprint "$pid" 2>&1 | head -12 | tee "$OUT/$name-footprint.txt"
        # iOS stops keyboards at roughly 48-70 MB; keep well under.
        mb=$(sed -n 's/.*Footprint: \([0-9]*\) MB.*/\1/p' "$OUT/$name-footprint.txt" | head -1)
        if [ "${mb:-0}" -ge 45 ]; then
            FAILED=1
            echo "::error::The keyboard uses $mb MB in phase '$name'"
        fi
    else
        echo "Keyboard process: not running"
    fi
    sleep 8
    limit 30 xcrun simctl io "$UDID" screenshot "$OUT/$name.png" >/dev/null 2>&1
    echo "--- host log"
    cat "$OUT/$name-host.log"
    echo "--- keyboard process after 16 s: $(keyboard_pid || true)"
    limit 90 xcrun simctl spawn "$UDID" log show --last 1m --style compact \
        --predicate 'process == "MSTransKeyboard" OR eventMessage CONTAINS[c] "MSTransKeyboard" OR eventMessage CONTAINS[c] "dichapple"' \
        > "$OUT/$name-system.log" 2>&1
    echo "--- system log (errors)"
    grep -i -E 'error|fail|crash|terminat|exception|denied|invalid|jetsam|kill|abort|signal' "$OUT/$name-system.log" | tail -60
    after=$(crash_reports)
    new=$(comm -13 <(echo "$before" | sort) <(echo "$after" | sort) | sed '/^$/d')
    if [ -n "$new" ]; then
        FAILED=1
        echo "::error::Keyboard crashed in phase '$name'"
        for f in $new; do
            cp "$REPORTS/$f" "$OUT/"
            echo "--- $f"
            head -c 20000 "$REPORTS/$f"
            echo
        done
    fi
    if grep -q "current=$KEYBOARD_ID" "$OUT/$name-host.log"; then
        if [ -z "$(keyboard_pid)" ]; then
            FAILED=1
            echo "::error::MSTrans was the current keyboard but its process is gone (phase '$name')"
        fi
        # The keys take ~290 pt; below that only the system's globe/dictation strip showed.
        height=$(sed -n 's/^KEYBOARD_HEIGHT=//p' "$OUT/$name-host.log" | tail -1)
        if [ "${height:-0}" -lt 250 ]; then
            FAILED=1
            echo "::error::The keyboard never drew its keys in phase '$name' (height ${height:-unknown} pt)"
        else
            echo "Keyboard drawn: $height pt"
        fi
    fi
    endgroup
}

run_phase fresh

group "Full Access, API key and a copied message"
GROUP_DIR=$(xcrun simctl get_app_container "$UDID" "$APP_ID" groups 2>/dev/null | awk -v g=group.$APP_ID '$1 == g { print $2 }')
echo "App Group container: ${GROUP_DIR:-none}"
TCC=$(find "$DATA" -name TCC.db -path '*Library/TCC/*' 2>/dev/null | head -1)
echo "TCC database: ${TCC:-none}"
limit 120 xcrun simctl shutdown "$UDID"
python3 - "$TCC" "$GROUP_DIR" <<'EOF'
import os, plistlib, sqlite3, sys, time
tcc, group_dir = sys.argv[1], sys.argv[2]
if tcc:
    db = sqlite3.connect(tcc)
    print(db.execute("select sql from sqlite_master where name = 'access'").fetchone()[0])
    cols = [r[1] for r in db.execute("pragma table_info(access)")]
    for client in ("com.mocsinh.dichapple.keyboard", "com.mocsinh.dichapple"):
        row = {"service": "kTCCServiceKeyboardNetwork", "client": client, "client_type": 0,
               "auth_value": 2, "auth_reason": 2, "auth_version": 1, "flags": 0,
               "last_modified": int(time.time()), "allowed": 1, "prompt_count": 1}
        keys = [k for k in row if k in cols]
        try:
            db.execute(f"insert or replace into access ({', '.join(keys)}) values ({', '.join('?' * len(keys))})",
                       [row[k] for k in keys])
        except Exception as e:
            print("TCC insert failed:", e)
    db.commit()
    for r in db.execute("select service, client, auth_value from access"):
        print("TCC", r)
if group_dir:
    path = os.path.join(group_dir, "Library/Preferences/group.com.mocsinh.dichapple.plist")
    values = {}
    if os.path.exists(path):
        with open(path, "rb") as f:
            values = plistlib.load(f)
    values["apiKey"] = "sk-ci-smoke-test"
    # Instant mode reads the clipboard as the keyboard opens; iOS then asks for paste permission.
    # The keys must still draw while that prompt waits (the main thread stays free).
    values["instantIncoming"] = True
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        plistlib.dump(values, f, fmt=plistlib.FMT_BINARY)
    print("App Group defaults:", sorted(values))
EOF
limit 180 xcrun simctl boot "$UDID"
limit 300 xcrun simctl bootstatus "$UDID" -b >/dev/null
printf '你好，请问这个型号的价格是多少？' | limit 30 xcrun simctl pbcopy "$UDID"
endgroup

run_phase full-access

group "What the keyboard saved"
[ -n "$GROUP_DIR" ] && plutil -p "$GROUP_DIR/Library/Preferences/group.com.mocsinh.dichapple.plist" 2>&1 | grep -v -i -E 'apikey|history'
endgroup

limit 30 xcrun simctl ui "$UDID" appearance dark
run_phase dark
limit 30 xcrun simctl ui "$UDID" appearance light

# The keyboard types a sentence through its own key handling as fast as a quick typist and
# records main-thread time per key, key layouts and late frames (Keyboard/TypingBenchmark.swift).
group "Typing benchmark setup"
xcrun simctl terminate "$UDID" "$HOST_ID" >/dev/null 2>&1
limit 120 xcrun simctl shutdown "$UDID"
python3 ci/typing-benchmark.py setup "$GROUP_DIR"
limit 180 xcrun simctl boot "$UDID"
limit 300 xcrun simctl bootstatus "$UDID" -b >/dev/null
endgroup

run_phase typing

group "Typing benchmark"
# The benchmark types about 210 keys and then holds delete: give it time to finish.
sleep 16
xcrun simctl terminate "$UDID" "$HOST_ID" >/dev/null 2>&1
limit 120 xcrun simctl shutdown "$UDID"
python3 ci/typing-benchmark.py report "$GROUP_DIR" "$OUT/typing-host.log" | tee "$OUT/typing-benchmark.txt" || FAILED=1
endgroup

# Screenshots of each tab of the app, light and dark, with a few sample translations.
group "App screens"
xcrun simctl terminate "$UDID" "$HOST_ID" >/dev/null 2>&1
limit 120 xcrun simctl shutdown "$UDID"
python3 - "$GROUP_DIR" <<'EOF'
import json, os, plistlib, sys, time, uuid
path = os.path.join(sys.argv[1], "Library/Preferences/group.com.mocsinh.dichapple.plist")
with open(path, "rb") as f:
    values = plistlib.load(f)
now = time.time() - 978307200  # Foundation's reference date
def item(side, source, translation, code, minutes, **extra):
    entry = dict(id=str(uuid.uuid4()).upper(), side=side, source=source, translation=translation,
                 languageCode=code, date=now - minutes * 60, starred=False)
    entry.update(extra)
    return entry
history = [
    item("me", "Dạ mẫu này giá 1.200 USD, bên em giao trong 2 tuần ạ.", "您好，这个型号的价格是1200美元，我们两周内发货。", "zh", 2,
         meaning="Chào anh, mẫu này giá 1.200 USD, bên em giao hàng trong vòng hai tuần."),
    item("partner", "你好，请问这个型号的价格是多少？", "Chào bạn, cho hỏi mẫu này giá bao nhiêu?", "zh", 3),
    item("partner", "Could you send me the packing list for the 4 containers?", "Bạn gửi giúp mình packing list của 4 container nhé?", "en", 90,
         note="“Packing list” là phiếu đóng gói hàng; giữ nguyên thuật ngữ."),
    item("me", "Em gửi anh hợp đồng bản cuối, anh xem giúp em nhé.", "I've attached the final contract. Could you please take a look?", "en", 1500,
         meaning="Em đã gửi kèm hợp đồng bản cuối. Anh xem giúp em được không?", starred=True),
]
values["history"] = json.dumps(history, ensure_ascii=False).encode()
values["glossary"] = "主轴 = trục chính\n数控机床 = máy CNC\npacking list = phiếu đóng gói\nMộc Sinh"
with open(path, "wb") as f:
    plistlib.dump(values, f, fmt=plistlib.FMT_BINARY)
print("sample data written")
EOF
limit 180 xcrun simctl boot "$UDID"
limit 300 xcrun simctl bootstatus "$UDID" -b >/dev/null
for appearance in light dark; do
    limit 30 xcrun simctl ui "$UDID" appearance "$appearance"
    for tab in translate history glossary settings; do
        limit 60 xcrun simctl launch --terminate-running-process "$UDID" "$APP_ID" -initialTab "$tab" >/dev/null
        sleep 4
        limit 30 xcrun simctl io "$UDID" screenshot "$OUT/app-$tab-$appearance.png" >/dev/null 2>&1 && echo "app-$tab-$appearance.png"
    done
done
new=$(crash_reports)
[ -n "$new" ] && { FAILED=1; echo "::error::Crash reports: $new"; for f in $new; do head -c 20000 "$REPORTS/$f"; done; }
endgroup

group "Keyboard diagnostics as the app reports them"
limit 60 xcrun simctl launch --terminate-running-process --stdout="$PWD/$OUT/diagnostics.txt" "$UDID" "$APP_ID" -printDiagnostics YES >/dev/null
sleep 4
cat "$OUT/diagnostics.txt"
if ! grep -q "Bàn phím chạy lần cuối: bước 4" "$OUT/diagnostics.txt"; then
    FAILED=1
    echo "::error::The diagnostics did not see the keyboard reach step 4"
fi
# Apple's codesign sets the flag on both executables; the parser must find it (iOS 26 needs it).
if [ "$(grep -c "Cờ chữ ký main binary: có" "$OUT/diagnostics.txt")" != 2 ]; then
    FAILED=1
    echo "::error::The diagnostics did not read the main-binary signing flag of the app and keyboard"
fi
endgroup

limit 120 xcrun simctl shutdown "$UDID" >/dev/null 2>&1
exit $FAILED
