"""Text rules shared with the app: Hanzi set (GLB-001), matching key (SRCH-010),
headword folding (GLB-005/006), English tokenisation (SRCH-013)."""
from __future__ import annotations

import re
import unicodedata

# GLB-001: Unified_Ideograph=Yes (Unicode 17+) plus U+3007.
HANZI_RANGES = (
    (0x3007, 0x3007),
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xFA0E, 0xFA0F), (0xFA11, 0xFA11), (0xFA13, 0xFA14), (0xFA1F, 0xFA1F),
    (0xFA21, 0xFA21), (0xFA23, 0xFA24), (0xFA27, 0xFA29),
    (0x20000, 0x2A6DF),
    (0x2A700, 0x2B81D),
    (0x2B820, 0x2CEAD),
    (0x2CEB0, 0x2EBE0),
    (0x2EBF0, 0x2EE5D),
    (0x30000, 0x3134A),
    (0x31350, 0x33479),
)
# Character class body usable inside a regex [...]
HANZI_CLASS = "".join(
    (f"\\U{a:08X}" if a == b else f"\\U{a:08X}-\\U{b:08X}") for a, b in HANZI_RANGES
)
_HANZI_RE = re.compile(f"[{HANZI_CLASS}]")

MIDDLE_DOTS = "・‧·･•"
_MIDDLE_DOT_RE = re.compile(f"[{MIDDLE_DOTS}]")
_APOSTROPHES_RE = re.compile("[’‘ʼ＇]")
TONE_VOWELS = "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ"
_SEPARATOR_RE = re.compile(f"[^{HANZI_CLASS}a-z0-9'·\\sü{TONE_VOWELS}]")
_WS_RE = re.compile(r"\s+")
_EN_SPLIT_RE = re.compile(r"[^a-z0-9]+")


def is_hanzi(ch: str) -> bool:
    return bool(_HANZI_RE.match(ch))


def has_hanzi(s: str) -> bool:
    return bool(_HANZI_RE.search(s))


def fold(s: str) -> str:
    """Headword folding: NFKC + middle-dot folding (DATA-071 'folded' key)."""
    return _MIDDLE_DOT_RE.sub("·", unicodedata.normalize("NFKC", s))


def matching_key(s: str) -> str:
    """SRCH-010 matching key."""
    s = unicodedata.normalize("NFKC", s)          # 1
    s = s.lower()                                  # 2
    s = _APOSTROPHES_RE.sub("'", s)                # 3
    s = s.replace("u:", "ü")                       # 4
    s = _MIDDLE_DOT_RE.sub("·", s)            # 5
    s = _SEPARATOR_RE.sub(" ", s)                  # 6
    return _WS_RE.sub(" ", s).strip()              # 7


def display_key(s: str) -> str:
    """SRCH-012 display normalisation: NFC + whitespace collapse + trim."""
    return _WS_RE.sub(" ", unicodedata.normalize("NFC", s)).strip()


def en_tokens(text: str) -> list[str]:
    """SRCH-013 English tokenisation."""
    out = []
    for t in _EN_SPLIT_RE.split(text.lower()):
        if not t:
            continue
        if len(t) > 3 and t.endswith("s") and not t.endswith("ss"):
            t = t[:-1]
        out.append(t)
    return out


def codepoints(s: str) -> int:
    return len(s)
