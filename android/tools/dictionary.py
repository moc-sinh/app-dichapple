"""
Builds MSTrans's large Chinese-Vietnamese dictionary from two dictionaries in the dictd format
(a .index and a .dict file each) that the owner has on their computer:

    python -I android/tools/dictionary.py OUT_DIR TrungViet-small Tu_dien_Han_ngu

(each path without .index/.dict). Writes into OUT_DIR:
- zh_vi_words.txt: "word<TAB>meaning<TAB>trust", the first meaning made short, sorted as Java sorts
  strings. trust 1: in the Hán ngữ dictionary (written by people); 0: only in TrungViet-small,
  much of which was made by machine through English ("自定义" is "tập quán" there), used last.
- han_viet.txt: "character<TAB>Sino-Vietnamese reading" (the first one given).

The files are not in the repository (their sources' terms are the owner's to judge): they go into
the APK built on the computer that has them, and MSTrans works without them.
"""
import re
import sys

B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
LONGEST_MEANING = 40


def number(text):
    value = 0
    for c in text:
        value = value * 64 + B64.index(c)
    return value


def entries(base):
    data = open(base + ".dict", "rb").read()
    seen = set()
    with open(base + ".index", encoding="utf-8", errors="replace") as index:
        for line in index:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or parts[0] in seen:
                continue
            seen.add(parts[0])
            start, length = number(parts[1]), number(parts[2])
            yield parts[0], data[start:start + length].decode("utf-8", errors="replace")


def han(c):
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿" or "豈" <= c <= "﫿"


TAGS = re.compile(r"<[^>]+>")
CIRCLED = re.compile(r"[①-⑳⓫-⓴❶-➓]")
LABEL = re.compile(r"^\s*(?:\([^)]{1,20}\)|〈[^〉]{1,20}〉|<[^>]{1,20}>|\[[^\]]{1,30}\]|【[^】]{1,20}】)\s*")
VIETNAMESE_LETTER = re.compile(r"[a-zA-ZÀ-ỹđĐ]")


def clean(sense):
    """One meaning without labels, examples in Chinese, or the other words given for it."""
    text = CIRCLED.sub(" ", TAGS.sub(" ", sense)).strip()
    while True:
        cut = LABEL.sub("", text)
        if cut == text:
            break
        text = cut
    for i, c in enumerate(text):
        if han(c):
            text = text[:i]
            break
    # "kích cỡ, kích thước, to nhỏ": the first of the words given for it.
    text = re.split(r"[,，(（]", text)[0]
    text = re.sub(r"\s+", " ", text).strip(" ,.:;-–—|")
    if not text or len(text) > LONGEST_MEANING or not VIETNAMESE_LETTER.search(text):
        return None
    # A surname or a name explained ("họ Tư Mã", "tên một loài…"): a name is read in Sino-Vietnamese instead.
    if re.match(r"(?i)(họ|tên|chỉ|dùng để|xem|cũng được viết)\s", text):
        return None
    return text


def short(senses):
    """The first meaning; a fruit rather than its tree ("苹果" is "quả táo" where both are given)."""
    found = [m for m in (clean(s) for s in senses) if m]
    if not found:
        return None
    if found[0].lower().startswith("cây "):
        for m in found[1:]:
            if re.match(r"(?i)(quả|trái)\s", m):
                return m
    return found[0]


def machine_made(senses):
    """
    TrungViet-small's entries made through English: an English word among the meanings ("Custom",
    "Webcam"), or three meanings and more listed as an English dictionary does ("自定义": "tập quán",
    "đã xác định người dùng", "Custom"). A capital first is no sign: "Tối nay", "Máy giặt" are right.
    """
    found = [m for m in (clean(s) for s in senses) if m]
    if not found:
        return True
    if any(re.fullmatch(r"[A-Za-z][A-Za-z ]*", m) for m in found):
        return True
    return len(found) >= 3


def reading(text):
    first = re.split(r"[|,，/;]", text)[0].strip().lower()
    return first if re.fullmatch(r"[a-zà-ỹđ]+", first) else None


def main():
    out, small, hanngu = sys.argv[1], sys.argv[2], sys.argv[3]
    words, readings = {}, {}
    # The Hán ngữ dictionary first: "[tian1ping2]cân tiểu ly. …" for a word, "禾(hòa)* xứng [cheng4] …" for a character.
    for head, body in entries(hanngu):
        if not head or not all(han(c) for c in head):
            continue
        if len(head) == 1:
            match = re.search(r"\)\*?\s+([^\[\s][^\[]*?)\s*\[", body)
            r = reading(match.group(1)) if match else None
            if r:
                readings[head] = r
            continue
        meaning = re.sub(r"^\s*\[[^\]]*\]", "", body)
        m = short(re.split(r"[;；]|\.\s", meaning))
        if m:
            words[head] = (m, 1)
    # TrungViet-small: "pinyin<br><b>reading</b><br><b>①</b> meaning; … <br><b>②</b> …".
    for head, body in entries(small):
        if not head or not all(han(c) for c in head) or "<br>" not in body:
            continue
        parts = body.split("<br>")
        if len(head) == 1:
            r = reading(TAGS.sub("", parts[1])) if len(parts) > 1 else None
            if r and head not in readings:
                readings[head] = r
            continue
        senses = []
        for part in parts[2:]:
            for piece in re.split(r"[;；]", CIRCLED.sub(" ", TAGS.sub(" ", part))):
                if piece.strip():
                    senses.append(piece)
        if head in words:
            # Both have it: a meaning written by people; this one's if the Hán ngữ one is none.
            continue
        if machine_made(senses):
            continue
        m = short(senses)
        if m:
            words[head] = (m, 0)
    order = sorted(words, key=lambda w: w.encode("utf-16-be"))
    with open(out + "/zh_vi_words.txt", "w", encoding="utf-8", newline="\n") as f:
        for w in order:
            meaning, trust = words[w]
            f.write(w + "\t" + meaning + "\t" + str(trust) + "\n")
    with open(out + "/han_viet.txt", "w", encoding="utf-8", newline="\n") as f:
        for c in sorted(readings):
            f.write(c + "\t" + readings[c] + "\n")
    trusted = sum(1 for m, t in words.values() if t)
    print("words", len(words), "trusted", trusted, "readings", len(readings))


if __name__ == "__main__":
    main()
