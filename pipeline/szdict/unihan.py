"""Unihan import (PIPE-080, DATA-190) and UCD radical mappings."""
from __future__ import annotations

import io
import re
import zipfile
from pathlib import Path

from .norm import en_tokens, is_hanzi
from .senses import parse_segments, plain_text

FIELDS = {"kDefinition", "kMandarin", "kCantonese", "kHanyuPinyin", "kHanyuPinlu", "kSimplifiedVariant", "kTraditionalVariant"}
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


_CACHE: dict[str, dict] = {}


def read_unihan_cached(zip_path: Path) -> dict[str, dict[str, str]]:
    key = str(zip_path)
    if key not in _CACHE:
        _CACHE[key] = read_unihan(zip_path)
    return _CACHE[key]


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


def radical_form_targets(unihan_zip: Path, equivalent: Path, cjk_radicals: Path) -> dict[str, list[str]]:
    """CJK Radicals Supplement / CJK Strokes code point -> unified ideographs whose entries it finds
    (DATA-071, PIPE-080 step 4): its equivalent unified ideograph and, when that ideograph is itself a
    radical form (kRSUnicode residual 0), the radical's main ideograph from CJKRadicals.txt (⻊ -> 𧾷, 足)."""
    eq = {r: u for r, u in read_equivalent_ideographs(equivalent).items()
          if 0x2E80 <= ord(r) <= 0x2EFF or 0x31C0 <= ord(r) <= 0x31EF}
    main: dict[str, str] = {}
    for line in cjk_radicals.read_text("utf-8").splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        parts = [x.strip() for x in line.split(";")]
        if len(parts) >= 3 and parts[2]:
            main.setdefault(parts[0].rstrip("'"), chr(int(parts[2], 16)))
    wanted = set(eq.values())
    rs: dict[str, str] = {}
    with zipfile.ZipFile(unihan_zip) as z:
        with z.open("Unihan_IRGSources.txt") as f:
            for line in io.TextIOWrapper(f, "utf-8"):
                if "\tkRSUnicode\t" not in line:
                    continue
                cp, _field, value = line.rstrip("\n").split("\t", 2)
                ch = chr(int(cp[2:], 16))
                if ch in wanted:
                    rs[ch] = value.split()[0]
    out: dict[str, list[str]] = {}
    for r, u in sorted(eq.items()):
        targets = [u]
        m = re.fullmatch(r"(\d+)'*\.(-?\d+)", rs.get(u, ""))
        if m and m.group(2) == "0" and main.get(m.group(1)) and main[m.group(1)] != u:
            targets.append(main[m.group(1)])
        out[r] = targets
    return out
