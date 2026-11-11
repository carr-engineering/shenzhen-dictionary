"""Fixture selection (ARCH-151): headwords and Unihan characters listed in data/fixture.json."""
from __future__ import annotations

import json

from .config import Config


def load_fixture(cfg: Config) -> None:
    data = json.loads((cfg.data / "fixture.json").read_text("utf-8"))
    cfg.fixture_words = set(data["headwords"])
    cfg.fixture_chars = set(data["unihan_chars"])
    cfg.lm_bigram_target = 2000
