"""Unihan import (PIPE-080, DATA-190) and UCD radical mappings."""
from __future__ import annotations

import io
import re
import zipfile
from pathlib import Path

from .norm import en_tokens, is_hanzi
from .senses import parse_segments, plain_text

FIELDS = {"kDefinition", "kMandarin", "kCantonese", "kHanyuPinyin", "kSimplifiedVariant", "kTraditionalVariant"}
_UPLUS = re.compile(r"U\+([0-9A-F]{4,6})")


def read_unihan(zip_path: Path, only: set[str] | None = None) -> dict[str, dict[str, str]]:
    """char -> {field: value} for the fields PIPE-080 uses."""
    out: dict[str, dict[str, str]] = {}
    with zipfile.ZipFile(zip_path) as z:
        for name in ("Unihan_Readings.txt", "Unihan_Variants.txt"):
            with z.open(name) as f:
                for line in io.TextIOWrapper(f, "utf-8"):
                    if line.startswith("#") or not line.strip():
                        continue
                    cp, field, value = line.rstrip("\n").split("\t", 2)
                    if field not in FIELDS:
                        continue
                    ch = chr(int(cp[2:], 16))
                    if only is not None and ch not in only:
                        continue
                    out.setdefault(ch, {})[field] = value
    return out


def variant_chars(value: str) -> list[str]:
    return [chr(int(m, 16)) for m in _UPLUS.findall(value)]


def read_equivalent_ideographs(path: Path) -> dict[str, str]:
    """Radical / stroke code point -> unified ideograph (EquivalentUnifiedIdeograph.txt)."""
    out: dict[str, str] = {}
    for line in path.read_text("utf-8").splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        src, dst = [x.strip() for x in line.split(";")]
        target = chr(int(dst, 16))
        if ".." in src:
            a, b = [int(x, 16) for x in src.split("..")]
        else:
            a = b = int(src, 16)
        for cp in range(a, b + 1):
            out[chr(cp)] = target
    return out


def definition_senses(defn: str, script: str) -> list[dict]:
    """kDefinition -> senses split at ';' (DATA-190); U+XXXX mentions become the character."""
    defn = _UPLUS.sub(lambda m: chr(int(m.group(1), 16)), defn)
    senses = []
    seen = set()
    for part in defn.split(";"):
        part = part.strip()
        if not part:
            continue
        segs = parse_segments(part)
        text = plain_text(segs, script)
        if not text or text in seen:
            continue
        seen.add(text)
        senses.append({"t": text, "src": "unihan", "tags": [], "seg": segs,
                       "tok": en_tokens(" ".join(s["t"] for s in segs if s["k"] == "t"))})
    return senses


def hanyu_pinyin_readings(value: str) -> list[str]:
    out = []
    for group in value.split():
        _loc, _, rds = group.partition(":")
        for r in rds.split(","):
            if r and r not in out:
                out.append(r)
    return out


def is_ideograph(ch: str) -> bool:
    cp = ord(ch)
    return is_hanzi(ch) or 0xF900 <= cp <= 0xFAFF or 0x2F800 <= cp <= 0x2FA1F
