#!/usr/bin/env python3
# /// script
# requires-python = ">=3.14"
# dependencies = []
# ///
"""Train the preset DEFLATE dictionaries of the entry bodies (contract/SCHEMA.md, schema version 2).

    uv run pipeline/build.py && uv run pipeline/make_body_dict.py && uv run pipeline/build.py

Reads the bodies of pipeline/out/{mandarin,cantonese}.db and writes pipeline/data/body-dict-<id>.bin
(32 KiB each: the content of a zstd-trained dictionary, used as a plain zlib preset dictionary). The
files are committed; a dataset is always decodable with the dictionary stored inside it (`body_dict`),
so retraining is only worth it when the body format changes. Needs Python 3.14 (`compression.zstd`).
"""
from __future__ import annotations

import sqlite3
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from compression import zstd  # noqa: E402

from szdict.config import Config  # noqa: E402
from szdict.enc import body_dict  # noqa: E402

SIZE = 32768


def main() -> None:
    cfg = Config()
    for name in ("mandarin", "cantonese"):
        con = sqlite3.connect(f"file:{cfg.out / (name + '.db')}?mode=ro", uri=True)
        old = body_dict(con)
        bodies = []
        for (blob,) in con.execute("SELECT body FROM entry ORDER BY id"):
            do = zlib.decompressobj(-15, old) if old else zlib.decompressobj(-15)
            bodies.append(do.decompress(blob) + do.flush())
        content = zstd.train_dict(bodies[::3], 110_000).dict_content[-SIZE:]

        def size(zd):
            total = 0
            for b in bodies:
                co = zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, zd) if zd else zlib.compressobj(9, zlib.DEFLATED, -15)
                total += len(co.compress(b) + co.flush())
            return total

        path = cfg.data / f"body-dict-{name}.bin"
        path.write_bytes(content)
        print(f"{name}: {len(bodies)} bodies, {sum(map(len, bodies))} bytes of JSON; deflate {size(None)} -> with dictionary {size(content)}; wrote {path}")


if __name__ == "__main__":
    main()
