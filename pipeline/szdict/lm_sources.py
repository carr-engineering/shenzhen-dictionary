"""Mandarin language model from the libime ARPA model (PIPE-070…073), with a frequency-table fallback."""
from __future__ import annotations

import math

from . import lm as lmmod
from .config import Config


def mandarin_lm(ds, cfg: Config):
    archive = cfg.src("libime_lm_sc")
    if archive.exists():
        arpa = lmmod.extract_arpa(cfg)
        uni = lmmod.read_arpa_unigrams(arpa)
        n_arpa = len(uni)
        # Blend the (written-register) libime unigrams with the conversational subtitle frequency
        # list so everyday words (你好, 谢谢) rank as a learner expects: p = mix*p_lm + (1-mix)*p_freq.
        mix = cfg.lm_unigram_mix
        total = max(1, ds.total_freq)
        for w in set(uni) | set(ds.freq):
            lp, bo = uni.get(w, (None, 0.0))
            p = (mix * math.exp(lp) if lp is not None else 0.0) + (1.0 - mix) * ds.freq.get(w, 0) / total
            uni[w] = (math.log(p), bo)
        lm = lmmod.build_vocab(ds, cfg, uni)
        lm.report["arpa_unigrams"] = n_arpa
        lm.report["unigram_mix"] = {"libime": mix, "frequency_list": round(1.0 - mix, 3)}
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


def mandarin_ngram(ds, lm, cfg: Config, log=print) -> None:
    """PIPE-087: the combination model (folded libime + subtitles), after the first-letter index so the
    byte budget is measured on the whole companion file."""
    import tempfile
    from pathlib import Path

    from . import lm_ngram
    archive = cfg.src("libime_lm_sc")
    if not archive.exists():
        return
    arpa = lmmod.extract_arpa(cfg)
    subs = cfg.src("opensubtitles_zh_cn")
    with tempfile.TemporaryDirectory() as tmp:
        trial = Path(tmp) / "trial-lm.db"
        lm.ngram = lm_ngram.build(lm.words, lm.logp, arpa, subs if subs.exists() else None, cfg,
                                  lambda model: lmmod.write_lm(ds, lm, trial, model), log)
    if subs.exists():
        s = cfg.lock["opensubtitles_zh_cn"]
        ds.lm_source = list(ds.lm_source) + [{
            "name": "OpenSubtitles 2018 zh_cn (OPUS), subtitle n-gram counts for the combinations",
            "version": s.get("version", ""), "licence": s.get("licence", ""), "url": s["url"], "sha256": s.get("sha256", ""),
            "credit": "http://www.opensubtitles.org/ ; P. Lison and J. Tiedemann, 2016, OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles, LREC 2016"}]
