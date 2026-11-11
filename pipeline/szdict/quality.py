"""Language-model quality gate (PIPE-075) against contract/lm-quality.json."""
from __future__ import annotations

import json

from . import lm as lmmod


def lm_quality(built: dict, cfg) -> tuple[bool, dict]:
    spec = json.loads((cfg.contract / "lm-quality.json").read_text("utf-8"))
    failures = []
    rep: dict = {"pass": True, "failures": failures}
    for mode, (ds, lm) in built.items():
        field = lmmod.lm_form(ds)
        sec = spec[mode]
        if cfg.fixture:
            cases = [c for c in sec["required"] if c.get("fixture")]
        else:
            cases = sec["required"]
        for c in cases:
            words = best(ds, lm, c["query"], len(c["expect"]), field, c.get("max_rank", 1))
            if c["expect"] not in words:
                failures.append({"mode": mode, "query": c["query"], "expect": c["expect"], "got": words})
        if not cfg.fixture and lm.has_bigrams and sec["broad"]:
            hits = 0
            for c in sec["broad"]:
                if c["expect"] in best(ds, lm, c["query"], len(c["expect"]), field, 3):
                    hits += 1
            rate = hits / max(1, len(sec["broad"]))
            rep[f"{mode}_top3_rate"] = round(rate, 3)
            if rate < sec["top3_threshold"]:
                failures.append({"mode": mode, "top3_rate": rate, "threshold": sec["top3_threshold"]})
    rep["pass"] = not failures
    return rep["pass"], rep


def best(ds, lm, query: str, n_words: int, field: str, k: int) -> list[list[str]]:
    """Single-word expectations are checked against the first-letter index order (the single-word
    result list); multi-word ones against the decoder's best combinations."""
    if n_words == 1:
        out: list[list[str]] = []
        for _neg, eid, _wid, _code, _alt, _syl in lm.abbr.get(query, ()):
            w = [ds.entries[eid][field]]
            if w not in out:
                out.append(w)
            if len(out) >= k:
                break
        return out
    return [words for words, _s in lmmod.decode_abbr(ds, lm, query, nbest=k)]
