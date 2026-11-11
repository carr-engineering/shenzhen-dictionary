"""Romanisation helpers: Pinyin (PIPE-021/022, GLB-015) and Jyutping readings."""
from __future__ import annotations

import re
import unicodedata
from functools import lru_cache
from pathlib import Path

DATA = Path(__file__).resolve().parent.parent / "data"

_MARKS = {1: "̄", 2: "́", 3: "̌", 4: "̀"}
_MARK_TO_TONE = {v: k for k, v in _MARKS.items()}
_TOKEN_RE = re.compile(r"([A-Za-zü Ü]+?)([1-5])")
PUNCT_TOKENS = {",", "·", "，", "、"}


def load_syllables(name: str) -> list[str]:
    out: list[str] = []
    for line in (DATA / name).read_text("utf-8").splitlines():
        if line.startswith("#"):
            continue
        out.extend(line.split())
    return sorted(set(out))


def load_aliases(name: str) -> dict[str, str]:
    out = {}
    for line in (DATA / name).read_text("utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        a, b = line.split("\t")
        out[a] = b
    return out


@lru_cache(maxsize=None)
def pinyin_inventory() -> frozenset[str]:
    return frozenset(load_syllables("pinyin_syllables.txt"))


@lru_cache(maxsize=None)
def jyutping_inventory() -> frozenset[str]:
    return frozenset(load_syllables("jyutping_syllables.txt"))


def mark_syllable(base: str, tone: int) -> str:
    """GLB-015 tone-mark placement. `base` keeps its case; ü already folded."""
    if tone not in _MARKS:
        return base
    low = base.lower()
    if "a" in low:
        i = low.index("a")
    elif "e" in low:
        i = low.index("e")
    elif "ou" in low:
        i = low.index("o")
    else:
        i = -1
        for j, ch in enumerate(low):
            if ch in "aeiouü":
                i = j
        if i < 0:  # syllabic nasal: m, n, ng, hm, hng
            for j, ch in enumerate(low):
                if ch in "mn":
                    i = j
                    break
        if i < 0:
            return base
    return unicodedata.normalize("NFC", base[: i + 1] + _MARKS[tone] + base[i + 1:])


def _fold_u(tok: str) -> str:
    return tok.replace("u:", "ü").replace("U:", "Ü")


@lru_cache(maxsize=200_000)
def parse_pinyin(raw: str) -> tuple:
    """Numbered CC-CEDICT reading -> (display, canonical, syllables, tones, proper, full).

    `full` is True when every token is a toned syllable of the inventory.
    canonical is lowercase; `cased` (returned last) keeps the source capitalisation.
    """
    inv = pinyin_inventory()
    disp, canon, cased, syls, tones = [], [], [], [], []
    proper = False
    full = True
    for tok in raw.split():
        t = _fold_u(tok)
        m = _TOKEN_RE.fullmatch(t)
        base = None
        if m:
            base = m.group(1)
            low = base.lower()
            if low not in inv and "v" in low and low.replace("v", "ü") in inv:
                base = base.replace("v", "ü").replace("V", "Ü")
                low = base.lower()
            if low not in inv:
                base = None
        if base is not None:
            tone = int(m.group(2))
            low = base.lower()
            disp.append(mark_syllable(base, tone))
            canon.append(f"{low}{tone}")
            cased.append(f"{base}{tone}")
            syls.append(low)
            tones.append(tone)
            if base[0].isupper():
                proper = True
        else:
            if tok not in PUNCT_TOKENS:
                full = False
            disp.append(tok)
            canon.append(tok.lower())
            cased.append(tok)
            syls.append(tok.lower())
            tones.append(0)
    if not syls:
        full = False
    return (" ".join(disp), " ".join(canon), tuple(syls), tuple(tones), proper, full, " ".join(cased))


_UNSPACED_RE = re.compile(r"[A-Za-zü]+?[1-5]|[A-Za-zü]+$")


def respace_numbered(raw: str, inv: frozenset[str] | None = None) -> str:
    """'qi1qie4' -> 'qi1 qie4' (PIPE-078). Tokens that are already single syllables are kept."""
    inv = inv or pinyin_inventory()
    out = []
    for tok in _fold_u(raw).split():
        if _TOKEN_RE.fullmatch(tok) and tok[:-1].lower() in inv:
            out.append(tok)
            continue
        parts = re.findall(r"[A-Za-zü]+?[1-5]", tok)
        if parts and "".join(parts) == tok and all(p[:-1].lower() in inv for p in parts):
            out.extend(parts)
        else:
            out.append(tok)
    return " ".join(out)


def strip_marks(s: str) -> tuple[str, list[tuple[int, int]]]:
    """Tone-marked text -> (plain lowercase-preserving text with ü, [(index, tone)])."""
    out = []
    marks = []
    for ch in unicodedata.normalize("NFD", s):
        if ch in _MARK_TO_TONE:
            marks.append((len(out) - 1, _MARK_TO_TONE[ch]))
        elif ch == "̈" and out and out[-1] in "uU":
            out[-1] = "ü" if out[-1] == "u" else "Ü"
        elif unicodedata.combining(ch):
            continue
        else:
            out.append(ch)
    return "".join(out), marks


def marked_to_numbered(s: str, n_syllables: int | None = None, neutral: bool = True) -> str | None:
    """'tóngshí' -> 'tong2 shi2'. Returns None when it does not segment against the inventory.

    Untoned syllables get tone 5 when `neutral`, else no digit.
    """
    inv = pinyin_inventory()
    res = []
    for word in s.replace("'", " ").split():
        plain, marks = strip_marks(word)
        low = plain.lower()
        if not re.fullmatch(r"[a-zü]+", low):
            return None
        mark_at = dict(marks)
        n = len(low)
        # best[i] = fewest-syllable segmentation of low[i:], each syllable holding <= 1 mark
        best: list[list[str] | None] = [None] * (n + 1)
        best[n] = []
        for i in range(n - 1, -1, -1):
            for j in range(min(n, i + 6), i, -1):
                if low[i:j] in inv and best[j] is not None:
                    if sum(1 for k in range(i, j) if k in mark_at) > 1:
                        continue
                    cand = [(i, j)] + best[j]
                    if best[i] is None or len(cand) < len(best[i]):
                        best[i] = cand
        if best[0] is None:
            return None
        for i, j in best[0]:
            tone = next((mark_at[k] for k in range(i, j) if k in mark_at), None)
            syl = low[i:j]
            if tone is None:
                res.append(syl + ("5" if neutral else ""))
            else:
                res.append(f"{syl}{tone}")
    if n_syllables is not None and len(res) != n_syllables:
        return None
    return " ".join(res) if res else None


_JYUT_RE = re.compile(r"([a-z]+)([1-6])")


@lru_cache(maxsize=200_000)
def parse_jyutping(raw: str) -> tuple:
    """Jyutping reading -> (display, canonical, syllables, tones, full)."""
    inv = jyutping_inventory()
    canon, syls, tones = [], [], []
    full = True
    for tok in raw.lower().split():
        m = _JYUT_RE.fullmatch(tok)
        if m and m.group(1) in inv:
            canon.append(tok)
            syls.append(m.group(1))
            tones.append(int(m.group(2)))
        else:
            full = False
            canon.append(tok)
            syls.append(tok)
            tones.append(0)
    if not syls:
        full = False
    c = " ".join(canon)
    return (c, c, tuple(syls), tuple(tones), full)


def is_key_token(s: str) -> bool:
    """True for tokens that survive SRCH-010 normalisation unchanged (syllables, letters, digits)."""
    return bool(_KEY_TOKEN_RE.fullmatch(s))


_KEY_TOKEN_RE = re.compile(r"[a-z0-9ü]+")


def reading_key(syllables) -> str:
    """Reading-index key: toneless syllables, punctuation tokens dropped, space-joined."""
    return " ".join(s for s in syllables if is_key_token(s))


def key_tones(syllables, tones) -> tuple:
    return tuple(t for s, t in zip(syllables, tones) if is_key_token(s))


def key_syllables(syllables) -> tuple:
    return tuple(s for s in syllables if is_key_token(s))


def sort_reading(s: str) -> str:
    """HSK-031 normalisation for sort keys."""
    return " ".join(s.strip().lower().replace("u:", "v").replace("ü", "v").split())
