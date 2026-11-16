"""Paths and build configuration."""
from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

PIPELINE = Path(__file__).resolve().parent.parent
REPO = PIPELINE.parent


@dataclass
class Config:
    fixture: bool = False
    out: Path = PIPELINE / "out"
    refs: Path = REPO / "refs"
    pinned: Path = PIPELINE / "sources-pinned"
    sources: Path = PIPELINE / "sources"
    data: Path = PIPELINE / "data"
    contract: Path = REPO / "contract"
    fixture_words: set[str] = field(default_factory=set)
    fixture_chars: set[str] = field(default_factory=set)
    # language-model configuration (PIPE-073, recorded in the build report)
    lm_bigram_target: int = 450_000
    lm_bigram_cap_per_left: int = 300
    lm_bigram_min_gain: float = 0.3
    abbr_cap_single: int = 300
    abbr_cap_two: int = 600
    abbr_cap_long: int = 400
    oov_penalty: float = -4.0
    lm_unigram_mix: float = 0.5
    # combination model (PIPE-087): companion file budget, subtitle weight / discount / minimum count
    lm_ngram_budget_bytes: int = 14_000_000
    lm_subs_weight: float = 0.5
    lm_subs_discount: float = 0.75
    lm_subs_min_count: int = 2
    use_fixture_sources: bool = True

    def __post_init__(self) -> None:
        self.lock = json.loads((PIPELINE / "sources.lock.json").read_text("utf-8"))["sources"]
        if self.fixture:
            self.out = self.out / "fixture"

    def src(self, name: str) -> Path:
        """Path of a locked source; the fixture build reads the committed excerpts instead, so it
        needs no downloads (make_fixture_sources.py regenerates them from the full sources)."""
        if self.fixture and self.use_fixture_sources:
            return self.data / "fixture-sources" / FIXTURE_FILES[name]
        return self.sources / self.lock[name]["file"]


FIXTURE_FILES = {
    "unihan": "Unihan.zip", "ucd_equivalent_unified_ideograph": "EquivalentUnifiedIdeograph.txt",
    "ucd_cjk_radicals": "CJKRadicals.txt", "libime_lm_sc": "lm_sc.1-3grams.arpa",
    "rime_essay_cantonese": "essay-cantonese.txt", "ud_cantonese_hk": "yue_hk-ud.conllu",
    "hkcancor": "hkcancor.zip", "opensubtitles_zh_cn": "opensubtitles-zh_cn.excerpt.txt.gz",
}


def git(*args: str) -> str:
    try:
        return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True,
                              check=True).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""
