#!/bin/bash
# Opens the Dịch Apple keyboard in an iOS Simulator and reports whether it starts:
# which keyboard the system shows, whether the extension process stays alive, its
# memory, crash reports and launch errors. Phase 1 is a fresh install; phase 2 adds
# Full Access, an API key and a copied message, as on a set-up iPhone.
# Run from the repo root after building DichApple for the simulator.
set -uo pipefail

APP=${1:-build/sim/Build/Products/Release-iphonesimulator/DichApple.app}
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
crash_reports() { ls "$REPORTS" 2>/dev/null | grep -i -E 'DichKeyboard|DichApple' || true; }
keyboard_pid() { pgrep -f 'DichKeyboard.appex/DichKeyboard' | head -1; }

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
codesign -dv --entitlements - "$APP/PlugIns/DichKeyboard.appex" 2>&1 | tail -20
plutil -p "$APP/PlugIns/DichKeyboard.appex/Info.plist"
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
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl install "$UDID" "$APP" || exit 1
xcrun simctl install "$UDID" "$HOST" || exit 1
# Dịch Apple first, so it is the keyboard that comes up.
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
    pkill -f 'DichKeyboard.appex/DichKeyboard' >/dev/null 2>&1
    sleep 1
    xcrun simctl launch --terminate-running-process \
        --stdout="$PWD/$OUT/$name-host.log" --stderr="$PWD/$OUT/$name-host.err" "$UDID" "$HOST_ID"
    sleep 8
    pid=$(keyboard_pid)
    if [ -n "$pid" ]; then
        echo "Keyboard process: running (pid $pid)"
        footprint "$pid" 2>&1 | head -12
    else
        echo "Keyboard process: not running"
    fi
    sleep 8
    xcrun simctl io "$UDID" screenshot "$OUT/$name.png" >/dev/null 2>&1
    echo "--- host log"
    cat "$OUT/$name-host.log"
    echo "--- keyboard process after 16 s: $(keyboard_pid || true)"
    xcrun simctl spawn "$UDID" log show --last 1m --style compact \
        --predicate 'process == "DichKeyboard" OR eventMessage CONTAINS[c] "DichKeyboard" OR eventMessage CONTAINS[c] "dichapple"' \
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
    if grep -q "current=$KEYBOARD_ID" "$OUT/$name-host.log" && [ -z "$(keyboard_pid)" ]; then
        FAILED=1
        echo "::error::Dịch Apple was the current keyboard but its process is gone (phase '$name')"
    fi
    endgroup
}

run_phase fresh

group "Full Access, API key and a copied message"
GROUP_DIR=$(xcrun simctl get_app_container "$UDID" "$APP_ID" groups 2>/dev/null | awk -v g=group.$APP_ID '$1 == g { print $2 }')
echo "App Group container: ${GROUP_DIR:-none}"
TCC=$(find "$DATA" -name TCC.db -path '*Library/TCC/*' 2>/dev/null | head -1)
echo "TCC database: ${TCC:-none}"
xcrun simctl shutdown "$UDID"
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
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        plistlib.dump(values, f, fmt=plistlib.FMT_BINARY)
    print("App Group defaults:", sorted(values))
EOF
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null
printf '你好，请问这个型号的价格是多少？' | xcrun simctl pbcopy "$UDID"
endgroup

run_phase full-access

group "What the keyboard saved"
[ -n "$GROUP_DIR" ] && plutil -p "$GROUP_DIR/Library/Preferences/group.com.mocsinh.dichapple.plist" 2>&1 | grep -v -i apikey
endgroup

xcrun simctl shutdown "$UDID" >/dev/null 2>&1
exit $FAILED
