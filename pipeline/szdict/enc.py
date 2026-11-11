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


def zjson(obj) -> bytes:
    co = zlib.compressobj(9, zlib.DEFLATED, -15)
    return co.compress(cjson(obj).encode("utf-8")) + co.flush()


def unzjson(buf: bytes):
    return json.loads(zlib.decompress(buf, -15).decode("utf-8"))
