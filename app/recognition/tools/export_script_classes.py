"""Writes assets/models/ink/script-classes.txt: which ink labels belong to only one script.

Source: OpenCC character tables (STCharacters.txt, TSCharacters.txt, HKVariants.txt), e.g. from an
opencc-kmp checkout:

  python3 tools/export_script_classes.py /path/to/opencc/text

Line 1: labels that are simplified-only (their traditional form is a different character).
Line 2: labels that are traditional-only (their simplified form is a different character).
Line 3: traditional labels whose Hong Kong standard form is a different character (HK prefers the other one).
"""
import sys
from pathlib import Path

opencc = Path(sys.argv[1])
here = Path(__file__).resolve().parent
ink = here.parent / "src/androidMain/assets/models/ink"
labels = set((ink / "labels.txt").read_text(encoding="utf-8").split("\n")) - {""}


def table(name):
    out = {}
    for line in (opencc / name).read_text(encoding="utf-8").splitlines():
        if "\t" in line:
            key, values = line.split("\t")
            out[key] = values.split(" ")
    return out


st, ts, hk = table("STCharacters.txt"), table("TSCharacters.txt"), table("HKVariants.txt")
simplified_only = sorted(c for c in labels if c in st and c not in st[c] and not (c in ts and c in ts[c]))
traditional_only = sorted(c for c in labels if c in ts and c not in ts[c] and not (c in st and c in st[c]))
not_hong_kong = sorted(c for c in labels if c in hk and c not in hk[c] and c not in simplified_only)
(ink / "script-classes.txt").write_text(
    "".join(simplified_only) + "\n" + "".join(traditional_only) + "\n" + "".join(not_hong_kong) + "\n", encoding="utf-8")
print(len(labels), "labels:", len(simplified_only), "simplified-only,", len(traditional_only), "traditional-only,", len(not_hong_kong), "non-HK forms")
print("".join(simplified_only[:40]), "".join(traditional_only[:40]), "".join(not_hong_kong[:40]))
