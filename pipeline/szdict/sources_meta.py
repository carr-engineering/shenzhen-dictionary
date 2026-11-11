"""`sources` / `lm_source` metadata (DATA-002, SET-042)."""
from __future__ import annotations

from .cedict import read_header
from .config import Config


def _lock(cfg: Config, name: str, title: str) -> dict:
    s = cfg.lock[name]
    return {"name": title, "version": s.get("version", ""), "licence": s.get("licence", ""), "url": s["url"],
            "sha256": s.get("sha256", "")}


def _hsk(cfg: Config) -> dict:
    ver = (cfg.pinned / "hsk" / "VERSION.txt").read_text("utf-8").splitlines()
    return {"name": "HSK syllabus vocabulary parser (HSK 3.0, 2026 update) + HSK parser patch",
            "version": ver[1] if len(ver) > 1 else "", "licence": "MIT (code), CC-BY-SA-4.0 (data)",
            "url": "https://github.com/Punpuf/hsk-syllabus-vocabulary-parser"}


def mandarin_sources(ds, cfg: Config) -> None:
    h = ds.cedict_header
    ds.sources = [
        {"name": "CC-CEDICT", "version": f"{h.get('version', '')}.{h.get('subversion', '')}", "date": h.get("date", ""),
         "licence": "CC-BY-SA-4.0", "url": "https://www.mdbg.net/chinese/dictionary?page=cc-cedict"},
        {"name": "FrequencyWords zh_cn (OpenSubtitles 2018 via OPUS)", "version": "2018", "licence": "CC-BY-SA-4.0",
         "url": "https://github.com/hermitdave/FrequencyWords"},
        _hsk(cfg),
        _lock(cfg, "unihan", "Unicode Han Database (Unihan)"),
        _lock(cfg, "ucd_equivalent_unified_ideograph", "UCD EquivalentUnifiedIdeograph.txt"),
    ]


def cantonese_sources(ds, cfg: Config) -> None:
    h = read_header(cfg.refs / "cedict_ts.u8")
    ds.sources = [
        {"name": "CC-Canto", "version": "2017-02-02", "licence": "CC-BY-SA-3.0", "url": "https://cccanto.org"},
        {"name": "CC-CEDICT Cantonese readings", "version": "2015-09-23", "licence": "CC-BY-SA-3.0",
         "url": "https://cccanto.org"},
        {"name": "CC-CEDICT", "version": f"{h.get('version', '')}.{h.get('subversion', '')}", "date": h.get("date", ""),
         "licence": "CC-BY-SA-4.0", "url": "https://www.mdbg.net/chinese/dictionary?page=cc-cedict"},
        _hsk(cfg),
        _lock(cfg, "unihan", "Unicode Han Database (Unihan)"),
        _lock(cfg, "ucd_equivalent_unified_ideograph", "UCD EquivalentUnifiedIdeograph.txt"),
    ] + list(getattr(ds, "corpus_sources", []))
