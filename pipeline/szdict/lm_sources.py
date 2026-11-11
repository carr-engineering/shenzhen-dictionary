"""Mandarin language model from the libime ARPA model (PIPE-070…073), with a frequency-table fallback."""
from __future__ import annotations

import math

from . import lm as lmmod
from .config import Config


def mandarin_lm(ds, cfg: Config):
    archive = cfg.src("libime_lm_sc")
    if archive.exists():
        arpa = lmmod.extract_arpa_12(cfg)
        uni = lmmod.read_arpa_unigrams(arpa)
        lm = lmmod.build_vocab(ds, cfg, uni)
        lm.report["arpa_unigrams"] = len(uni)
        backoffs = {w: bo for w, (_lp, bo) in uni.items()}
        lmmod.add_bigrams(lm, cfg, lmmod.iter_arpa_bigrams(arpa, lm.word_id), backoffs)
        s = cfg.lock["libime_lm_sc"]
        ds.lm_source = [{"name": "libime lm_sc.arpa", "version": s.get("version", ""),
                         "licence": s.get("licence", ""), "url": s["url"], "sha256": s.get("sha256", "")}]
    else:
        total = max(1, ds.total_freq)
        uni = {w: (math.log(c / total), 0.0) for w, c in ds.freq.items()}
        lm = lmmod.build_vocab(ds, cfg, uni)
        lm.report["fallback"] = "libime model not available: unigrams from the frequency table, no bigrams"
        ds.lm_source = [{"name": "FrequencyWords zh_cn unigrams (fallback: libime model unavailable)",
                         "version": "2018", "licence": "CC-BY-SA-4.0",
                         "url": "https://github.com/hermitdave/FrequencyWords"}]
    return lm
