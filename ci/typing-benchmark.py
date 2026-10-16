"""Typing benchmark helper for ci/keyboard-smoke.sh.

setup  GROUP_DIR           turns the benchmark on for the next keyboard launch
report GROUP_DIR HOST_LOG  prints the keyboard's measurements and checks the typed text
"""
import os
import plistlib
import re
import sys
import unicodedata

# A frame on a 120 Hz iPhone. 95 of 100 key events must be done well inside it.
FRAME_MS = 8.3
# Key layouts per key: pressing a key must not move the keys.
MAX_LAYOUTS_PER_KEY = 0.2

# Keyboard/TypingBenchmark.swift types this before it holds delete. Twenty a's give nineteen:
# in Telex the third one takes the hat off "â" (aa -> â, aaa -> aa), as in Unikey and EVKey.
EXPECTED = ("Chào anh bên em đã gửi báo giá rồi nhé. Anh xem giúp em với ạ " + "a" * 19 + " "
            "người người người người người người hahahahahahahahahahahahahahahahahahahaha "
            "chưa người mưa w giá 500k, ok?")


def prefs_path(group_dir):
    return os.path.join(group_dir, "Library/Preferences/group.com.mocsinh.dichapple.plist")


def load(group_dir):
    with open(prefs_path(group_dir), "rb") as f:
        return plistlib.load(f)


def setup(group_dir):
    values = load(group_dir)
    values["typingBenchmark"] = True
    # No paste prompt in the way: only the copy offer stays on, as in daily use.
    values["instantIncoming"] = False
    values.pop("typingBenchmarkResult", None)
    with open(prefs_path(group_dir), "wb") as f:
        plistlib.dump(values, f, fmt=plistlib.FMT_BINARY)
    print("benchmark on")


def report(group_dir, host_log):
    values = load(group_dir)
    result = values.get("typingBenchmarkResult", "")
    print(result or "no result")
    # What the keyboard's typing log noted as slow (Keyboard/TypingLog.swift), with the line before.
    log = values.get("typingLog", [])
    slow = [i for i, line in enumerate(log) if "\u23f1" in line or "\u231b" in line]
    if slow:
        print("Slow moments in the keyboard's typing log:")
        for i in slow[:30]:
            if i > 0:
                print("   ", log[i - 1])
            print("   ", log[i])
    failures = [] if result else ["the keyboard wrote no benchmark result"]
    for m in re.finditer(r"^(.+?): \d+ events avg=[\d.]+ms p95=([\d.]+)ms", result, re.M):
        if float(m.group(2)) > FRAME_MS:
            failures.append(f"{m.group(1)}: 95% of key events took up to {m.group(2)} ms, over a 120 Hz frame")
    m = re.search(r"layouts=\d+ \(([\d.]+)/key\)", result)
    if m and float(m.group(1)) > MAX_LAYOUTS_PER_KEY:
        failures.append(f"each key press laid out the keys {m.group(1)} times")
    want = unicodedata.normalize("NFC", EXPECTED)
    seen = []
    with open(host_log, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("FIELD_TEXT="):
                seen.append(unicodedata.normalize("NFC", line[len("FIELD_TEXT="):].rstrip("\n")))
    # The longest text the field held is the one right before delete was held.
    typed = max(seen, key=len, default="")
    print("Typed:   ", typed)
    print("Expected:", EXPECTED)
    print("After holding delete:", seen[-1] if seen else "")
    print("Text:", "match" if want in seen else "MISMATCH")
    if want not in seen:
        failures.append("the field never held the expected text")
    for failure in failures:
        print(f"::error::Typing benchmark: {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1] == "setup":
        setup(sys.argv[2])
    else:
        sys.exit(report(sys.argv[2], sys.argv[3]))
