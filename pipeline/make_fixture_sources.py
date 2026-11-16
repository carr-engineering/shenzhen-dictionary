#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy"]
# ///
"""Regenerate pipeline/data/fixture-sources/ — small excerpts of the downloaded sources restricted to
the fixture vocabulary (data/fixture.json), so `build.py --fixture` runs without any download.

    uv run pipeline/fetch.py && uv run pipeline/make_fixture_sources.py
"""
from __future__ import annotations

import gzip
import io
import re
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from szdict import lm as lmmod  # noqa: E402
from szdict import unihan as uh  # noqa: E402
from szdict.config import Config  # noqa: E402
from szdict.fixture import load_fixture  # noqa: E402
from szdict.cantonese import build_cantonese  # noqa: E402
from szdict.mandarin import build_hsk, build_mandarin  # noqa: E402
from szdict.core import assign_frequency  # noqa: E402
from szdict.lm_sources import mandarin_lm  # noqa: E402
from szdict.yue_corpus import read_hkcancor  # noqa: E402

ZIP_DATE = (2026, 1, 1, 0, 0, 0)


def zwrite(z: zipfile.ZipFile, name: str, text: str) -> None:
    info = zipfile.ZipInfo(name, ZIP_DATE)
    info.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info, text.encode("utf-8"))


def main() -> None:
    cfg = Config(fixture=True)
    cfg.use_fixture_sources = False                 # read the full downloads
    load_fixture(cfg)
    out = cfg.data / "fixture-sources"
    out.mkdir(parents=True, exist_ok=True)
    man = build_mandarin(cfg)
    rows = build_hsk(man, cfg)
    lm = mandarin_lm(man, cfg)
    assign_frequency(man, "simp", dict(zip(lm.words, lm.logp)), lm.observed)
    can, _clm = build_cantonese(man, rows, cfg)
    simp = {e["simp"] for e in man.entries if not e["unihan"]}
    trad = {e["trad"] for e in can.entries if not e["unihan"]}
    chars = set("".join(simp) + "".join(trad) + "".join(e["trad"] + e["simp"] for e in man.entries + can.entries))
    chars |= set(cfg.fixture_chars)

    # UCD files (small) are copied whole
    for name, fn in (("ucd_equivalent_unified_ideograph", "EquivalentUnifiedIdeograph.txt"), ("ucd_cjk_radicals", "CJKRadicals.txt")):
        shutil.copyfile(cfg.src(name), out / fn)
    targets = set(uh.read_equivalent_ideographs(cfg.src("ucd_equivalent_unified_ideograph")).values())

    # Unihan excerpt
    with zipfile.ZipFile(cfg.src("unihan")) as zin, zipfile.ZipFile(out / "Unihan.zip", "w") as zout:
        for name in ("Unihan_IRGSources.txt", "Unihan_Readings.txt", "Unihan_Variants.txt"):
            keep = []
            for line in io.TextIOWrapper(zin.open(name), "utf-8"):
                if line.startswith("#") or not line.strip():
                    continue
                cp, field, _rest = line.split("\t", 2)
                ch = chr(int(cp[2:], 16))
                if name == "Unihan_IRGSources.txt":
                    if field == "kRSUnicode" and ch in targets:
                        keep.append(line)
                elif ch in chars and field in uh.FIELDS:
                    keep.append(line)
            zwrite(zout, name, f"# excerpt of {name} for the fixture build\n" + "".join(keep))

    # libime ARPA excerpt: unigrams of fixture words and their characters, bigrams between fixture words
    arpa = lmmod.extract_arpa(cfg)
    vocab = simp | set("".join(simp))
    uni, big, tri = [], [], []
    section = 0
    with open(arpa, encoding="utf-8") as f:
        for line in f:
            if line.startswith("\\"):
                section = {"\\1-grams": 1, "\\2-grams": 2, "\\3-grams": 3}.get(line[:8], 0)
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            if section == 1 and parts[1] in vocab:
                uni.append(line)
            elif section == 2:
                a, _, b = parts[1].partition(" ")
                if a in simp and b in simp:
                    big.append(line)
            elif section == 3:
                a, b, c = parts[1].split(" ")
                if a in simp and b in simp and c in simp:
                    tri.append(line)
    (out / "lm_sc.1-3grams.arpa").write_text(
        f"\\data\\\nngram 1={len(uni)}\nngram 2={len(big)}\nngram 3={len(tri)}\n\n\\1-grams:\n{''.join(uni)}\n"
        f"\\2-grams:\n{''.join(big)}\n\\3-grams:\n{''.join(tri)}\n", "utf-8")
    (out / "lm_sc.1-2grams.arpa").unlink(missing_ok=True)

    # subtitle excerpt (PIPE-085): the first 20,000 lines written only with fixture characters (and digits)
    subs_src = cfg.src("opensubtitles_zh_cn")
    if subs_src.exists():
        keep_lines = []
        han = re.compile(r"[\u3400-\u9fff]")
        with gzip.open(subs_src, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                hz = han.findall(line)
                if len(hz) >= 2 and all(ch in chars for ch in hz):
                    keep_lines.append(line)
                    if len(keep_lines) >= 20000:
                        break
        with open(out / "opensubtitles-zh_cn.excerpt.txt.gz", "wb") as raw, \
                gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write("".join(keep_lines).encode("utf-8"))

    # Cantonese corpora excerpts
    essay = [ln for ln in cfg.src("rime_essay_cantonese").read_text("utf-8").splitlines()
             if ln.split("\t")[0] in trad]
    (out / "essay-cantonese.txt").write_text("\n".join(essay) + "\n", "utf-8")
    sents = [s for s in read_hkcancor(cfg) if sum(1 for w in s if w in trad) >= 3][:400]
    body = "".join("\t<sent>\n\t\t<sent_tag>\n" + "".join(f"\t\t\t{w}/x/x/\n" for w in s) + "\t\t</sent_tag>\n\t</sent>\n"
                   for s in sents)
    with zipfile.ZipFile(out / "hkcancor.zip", "w") as z:
        zwrite(z, "hkcancor/data/utf8/excerpt", body)
    ud = cfg.src("ud_cantonese_hk").read_text("utf-8").split("\n\n")[:40]
    (out / "yue_hk-ud.conllu").write_text("\n\n".join(ud) + "\n\n", "utf-8")
    (out / "README.txt").write_text(
        "Excerpts of the locked sources (pipeline/sources.lock.json) restricted to the fixture vocabulary.\n"
        "Generated by pipeline/make_fixture_sources.py; licences as in the lock file.\n", "utf-8")
    for p in sorted(out.iterdir()):
        print(f"{p.stat().st_size:8d} {p.name}")


if __name__ == "__main__":
    main()
