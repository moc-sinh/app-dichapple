"""Typing benchmark helper for ci/keyboard-smoke.sh.

setup  GROUP_DIR           turns the benchmark on for the next keyboard launch
report GROUP_DIR HOST_LOG  prints the keyboard's measurements and checks the typed text
"""
import os
import plistlib
import sys
import unicodedata

# Keyboard/TypingBenchmark.swift types this before it holds delete.
EXPECTED = ("Chào anh bên em đã gửi báo giá rồi nhé. Anh xem giúp em với ạ aaaaaaaaaaaaaaaaaaaa "
            "người người người người người người hahahahahahahahahahahahahahahahahahahaha")


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
    print(load(group_dir).get("typingBenchmarkResult", "no result"))
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


if __name__ == "__main__":
    if sys.argv[1] == "setup":
        setup(sys.argv[2])
    else:
        report(sys.argv[2], sys.argv[3])
