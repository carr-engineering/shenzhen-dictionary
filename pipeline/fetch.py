#!/usr/bin/env python3
"""Fetch the large third-party inputs pinned in sources.lock.json into pipeline/sources/ (PIPE-162).

    uv run pipeline/fetch.py            # download what is missing, verify SHA-256
    uv run pipeline/fetch.py --update   # (re)record size + SHA-256 of what was downloaded
"""
from __future__ import annotations

import hashlib
import json
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCK = HERE / "sources.lock.json"
DEST = HERE / "sources"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "dataset-pipeline-fetch/1"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    tmp.replace(path)


def main(argv: list[str]) -> int:
    update = "--update" in argv
    lock = json.loads(LOCK.read_text("utf-8"))
    ok = True
    for name, src in sorted(lock["sources"].items()):
        path = DEST / src["file"]
        if not path.exists():
            print(f"fetch  {name}: {src['url']}", flush=True)
            try:
                download(src["url"], path)
            except Exception as e:  # noqa: BLE001
                print(f"FAILED {name}: {e}")
                ok = ok and bool(src.get("optional"))
                continue
        digest = sha256_file(path)
        if update or not src.get("sha256"):
            src["sha256"] = digest
            src["size"] = path.stat().st_size
            print(f"pinned {name}: {digest}")
        elif digest != src["sha256"]:
            print(f"HASH MISMATCH {name}: expected {src['sha256']} got {digest}")
            ok = False
        else:
            print(f"ok     {name}")
    if update or any(True for _ in ()):
        pass
    LOCK.write_text(json.dumps(lock, ensure_ascii=False, indent=2, sort_keys=True) + "\n", "utf-8")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
