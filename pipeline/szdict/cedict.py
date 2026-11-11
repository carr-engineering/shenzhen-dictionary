"""CC-CEDICT format reader (PIPE-020) and CC-Canto / readings-file line readers."""
from __future__ import annotations

import re
from pathlib import Path

LINE_RE = re.compile(r"(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")
CANTO_RE = re.compile(r"(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+\{([^}]*)\}\s*(?:/(.*)/)?\s*(#.*)?$")


def read_header(path: Path) -> dict:
    meta = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                break
            m = re.match(r"#!\s*(\w+)=(.*)$", line.strip())
            if m:
                meta[m.group(1)] = m.group(2)
            m = re.match(r"#\s*Version\s+(\S+)", line.strip())
            if m:
                meta["version"] = m.group(1)
    return meta


def read_cedict(path: Path, report: dict | None = None):
    """Yield (line_no, trad, simp, reading, [glosses])."""
    bad = 0
    seen = set()
    dup = 0
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\r\n")
            if not line.strip() or line.startswith("#"):
                continue
            m = LINE_RE.match(line)
            if not m:
                bad += 1
                continue
            if line in seen:
                dup += 1
                continue
            seen.add(line)
            trad, simp, reading, body = m.groups()
            glosses = [g.strip() for g in body.split("/") if g.strip()]
            yield n, trad, simp, " ".join(reading.split()), glosses
    if report is not None:
        report["malformed_lines"] = bad
        report["duplicate_lines"] = dup
