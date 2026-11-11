"""Primitive encodings of contract/SCHEMA.md."""
from __future__ import annotations

import json
import zlib


def varint(n: int) -> bytes:
    if n < 0:
        raise ValueError("varint must be unsigned")
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def read_varints(buf: bytes) -> list[int]:
    out, n, shift = [], 0, 0
    for b in buf:
        n |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            out.append(n)
            n, shift = 0, 0
    return out


def delta_list(ids) -> bytes:
    out = bytearray()
    prev = 0
    for i, v in enumerate(ids):
        out += varint(v if i == 0 else v - prev)
        prev = v
    return bytes(out)


def read_delta_list(buf: bytes) -> list[int]:
    out, acc = [], 0
    for i, v in enumerate(read_varints(buf)):
        acc = v if i == 0 else acc + v
        out.append(acc)
    return out


def plain_list(ids) -> bytes:
    return b"".join(varint(v) for v in ids)


def cjson(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def zjson(obj, zdict: bytes | None = None) -> bytes:
    """Raw DEFLATE of compact JSON, with the dataset's preset dictionary when it has one."""
    if zdict:
        co = zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, zdict)
    else:
        co = zlib.compressobj(9, zlib.DEFLATED, -15)
    return co.compress(cjson(obj).encode("utf-8")) + co.flush()


def unzjson(buf: bytes, zdict: bytes | None = None):
    do = zlib.decompressobj(-15, zdict) if zdict else zlib.decompressobj(-15)
    return json.loads((do.decompress(buf) + do.flush()).decode("utf-8"))


def body_dict(con) -> bytes | None:
    """The preset dictionary stored in a dataset file (`body_dict`), or None."""
    try:
        row = con.execute("SELECT dict FROM body_dict ORDER BY id LIMIT 1").fetchone()
    except Exception:  # noqa: BLE001 - table absent
        return None
    return bytes(row[0]) if row else None


def derive_reading_tokens(canonical: str) -> tuple[tuple[str, ...], tuple[int, ...]]:
    """What the app derives from `reading_canonical` when `syllables` / `tones` are NULL (schema
    version 2): tokens split at spaces, a trailing digit being the tone."""
    syl, tones = [], []
    for raw in canonical.strip().split(" "):
        if not raw:
            continue
        if raw[-1].isdigit() and len(raw) > 1:
            syl.append(raw[:-1].lower().replace("u:", "ü").replace("v", "ü"))
            tones.append(int(raw[-1]))
        else:
            syl.append(raw.lower().replace("u:", "ü"))
            tones.append(0)
    return tuple(syl), tuple(tones)
