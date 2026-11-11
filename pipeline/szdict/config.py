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

    def __post_init__(self) -> None:
        self.lock = json.loads((PIPELINE / "sources.lock.json").read_text("utf-8"))["sources"]
        if self.fixture:
            self.out = self.out / "fixture"

    def src(self, name: str) -> Path:
        return self.sources / self.lock[name]["file"]


def git(*args: str) -> str:
    try:
        return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True,
                              check=True).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""
