"""Build orchestration: datasets, LM companions, widget projections, report."""
from __future__ import annotations

import hashlib
import json
import math
import time
from pathlib import Path

from . import lm as lmmod
from .config import PIPELINE, REPO, Config, git
from .core import (SCHEMA_VERSION, MAX_WORD_LEN, Dataset, assign_frequency, entry_gloss, sha256_file,
                   write_main)
from .enc import cjson


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def input_hash(cfg: Config) -> str:
    files = sorted(
        list((REPO / "refs").glob("*.u8")) + list((REPO / "refs").glob("*.txt")) + list((REPO / "refs").glob("*.tsv"))
        + [p for p in cfg.pinned.rglob("*") if p.is_file()]
        + [p for p in cfg.data.rglob("*") if p.is_file()]
        + list((PIPELINE / "szdict").glob("*.py")) + [PIPELINE / "build.py", PIPELINE / "sources.lock.json"]
        + [cfg.contract / "labels.json"])
    h = hashlib.sha256()
    for p in files:
        h.update(str(p.relative_to(REPO)).encode() + b"\0" + sha256_file(p).encode() + b"\n")
    return h.hexdigest()


def version_info(cfg: Config, source_date: str) -> dict:
    if cfg.fixture:
        return {"dataset_version": source_date + "+fixture", "pipeline_commit": "0" * 40,
                "built_at": "2026-01-01T00:00:00Z"}
    short = git("log", "-1", "--format=%h", "--", "pipeline", "contract", "refs") or "nogit"
    full = git("log", "-1", "--format=%H", "--", "pipeline", "contract", "refs") or "0" * 40
    stamp = git("log", "-1", "--format=%ct", "--", "pipeline", "contract", "refs")
    when = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(int(stamp))) if stamp else ""
    return {"dataset_version": f"{source_date}+{short}", "pipeline_commit": full,
            "built_at": when or "1970-01-01T00:00:00Z"}


def base_meta(ds: Dataset, cfg: Config, ver: dict, ihash: str) -> None:
    ds.meta.update({
        "schema_version": str(SCHEMA_VERSION), "dataset_id": ds.id,
        "dataset_version": ver["dataset_version"], "pipeline_commit": ver["pipeline_commit"],
        "input_hash": ihash, "language": ds.language, "romanization": ds.romanization,
        "tone_count": str(ds.tone_count), "max_word_len": str(MAX_WORD_LEN),
        "total_frequency": str(ds.total_freq), "has_frequency_data": "1", "has_hsk": "1",
        "sources": cjson(ds.sources), "asset_packs": "[]", "decomposition_hashes": "{}",
        "abbrev_penalty": f"{math.log(0.5):.6f}", "partial_penalty": f"{math.log(0.7):.6f}",
        "max_syllable_len": str(max(len(s) for s in ds.syllables)),
        "freq_rank_lm_fill": "1",
    })
    ds.meta_built_at = ver["built_at"]


def finish_dataset(ds: Dataset, lm, cfg: Config, report: dict) -> None:
    out = cfg.out
    out.mkdir(parents=True, exist_ok=True)
    lm_path = out / f"{ds.id}-lm.db"
    lmmod.write_lm(ds, lm, lm_path)
    ds.meta.update({
        "has_bigram_model": "1" if lm.has_bigrams else "0",
        "lm_word_count": str(len(lm.words)),
        "lm_file": cjson({"name": lm_path.name, "sha256": sha256_file(lm_path), "size": lm_path.stat().st_size}),
        "lm_source": cjson(ds.lm_source),
    })
    counts = write_main(ds, out / f"{ds.id}.db")
    # widget projection (DATA-130)
    rows = []
    for r in sorted(ds.hsk_rows, key=lambda r: r["ord"]):
        e = ds.entries[r["entry_id"]]
        rows.append({"id": e["id"], "key": e["nkey"],
                     "levels": sorted({r["level"], *[x["l"] for x in r["extra_levels"]]}),
                     "simp": e["simp"], "trad": e["trad"], "reading": e["display"], "gloss": entry_gloss(ds, e)})
    wpath = out / f"{ds.id}-widget.json"
    wpath.write_text(cjson({"dataset_version": ds.meta["dataset_version"], "rows": rows}), "utf-8")
    counts["widget_rows"] = len(rows)
    counts["widget_size"] = wpath.stat().st_size
    ds.report["tables"] = counts
    ds.report["lm"] = lm.report
    ds.report["meta"] = {k: ds.meta[k] for k in ("dataset_version", "pipeline_commit", "input_hash")}
    report[ds.id] = ds.report


def main(args) -> int:
    from .mandarin import build_hsk, build_hsk_chars, build_mandarin
    from .fixture import load_fixture
    cfg = Config(fixture=args.fixture)
    if cfg.fixture:
        load_fixture(cfg)
    report: dict = {"fixture": cfg.fixture, "gates": {}}
    ihash = input_hash(cfg)
    t0 = time.time()

    log("mandarin: parsing sources")
    man = build_mandarin(cfg)
    source_date = man.cedict_header.get("date", "1970-01-01")[:10].replace("-", ".")
    ver = version_info(cfg, source_date)
    hsk_rows = build_hsk(man, cfg)
    log(f"mandarin: {len(man.entries)} entries, {len(hsk_rows)} HSK rows")

    from .lm_sources import mandarin_lm
    mlm = mandarin_lm(man, cfg)
    assign_frequency(man, "simp", dict(zip(mlm.words, mlm.logp)), mlm.observed)
    build_hsk_chars(man, cfg)
    lmmod.build_abbr(man, mlm, cfg)
    from .sources_meta import mandarin_sources
    mandarin_sources(man, cfg)
    base_meta(man, cfg, ver, ihash)
    if args.only != "cantonese":
        finish_dataset(man, mlm, cfg, report)
        log(f"mandarin: written ({report['mandarin']['tables']['file_size']} bytes)")

    if args.only != "mandarin":
        from .cantonese import build_cantonese
        can, clm = build_cantonese(man, hsk_rows, cfg)
        from .sources_meta import cantonese_sources
        cantonese_sources(can, cfg)
        base_meta(can, cfg, ver, ihash)
        finish_dataset(can, clm, cfg, report)
        log(f"cantonese: written ({report['cantonese']['tables']['file_size']} bytes)")

    ok = True
    built = {}
    if args.only != "cantonese":
        built["mandarin"] = (man, mlm)
    if args.only != "mandarin":
        built["cantonese"] = (can, clm)
    from . import golden
    from .quality import lm_quality
    g_ok, diffs = golden.run(built, cfg, args.update_golden)
    report["gates"]["11_golden_queries"] = {"pass": g_ok, "failures": diffs}
    if not g_ok:
        print(f"GATE FAIL golden queries: {list(diffs)[:8]} (rerun with --update-golden to accept)", flush=True)
    q_ok, q_rep = lm_quality(built, cfg)
    report["gates"]["14_lm_quality"] = q_rep
    if not q_ok:
        print(f"GATE FAIL LM quality: {q_rep['failures'][:8]}", flush=True)
    ok = g_ok and q_ok
    if not args.no_validate:
        from .validate import validate_all
        ok = validate_all(cfg, report, only=args.only) and ok
    (cfg.out / "build-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1, sort_keys=True) + "\n", "utf-8")
    if cfg.fixture and ok and not args.only:
        # the fixture output is committed for the app's tests (ARCH-151)
        dest = cfg.contract / "fixture"
        dest.mkdir(exist_ok=True)
        for name in ("mandarin.db", "mandarin-lm.db", "mandarin-widget.json", "cantonese.db", "cantonese-lm.db",
                     "cantonese-widget.json"):
            (dest / name).write_bytes((cfg.out / name).read_bytes())
    log(f"done in {time.time() - t0:.1f}s — gates {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1
