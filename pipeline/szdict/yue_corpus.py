"""Cantonese frequency table (DATA-189) and language model (PIPE-074) from the corpora of PIPE-077."""
from __future__ import annotations

import io
import math
import re
import unicodedata
import zipfile
from collections import Counter, defaultdict

from . import lm as lmmod
from .config import Config
from .norm import is_hanzi

ESSAY_TOTAL = 20_000_000          # essay weights are rescaled to this many counts
WEIGHTS = {"hkcancor": 10, "ud_cantonese_hk": 10, "tatoeba_yue": 3}
DISCOUNT = 0.75
MIN_BIGRAM_COUNT = 2


def _lock_source(cfg: Config, name: str, title: str, role: str) -> dict:
    s = cfg.lock[name]
    return {"name": title, "version": s.get("version", ""), "licence": s.get("licence", ""), "url": s["url"],
            "sha256": s.get("sha256", ""), "role": role}


def read_essay(cfg: Config) -> dict[str, float]:
    path = cfg.src("rime_essay_cantonese")
    out: dict[str, float] = {}
    if not path.exists():
        return out
    for line in path.read_text("utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        try:
            w = float(parts[1])
        except ValueError:
            continue
        out[parts[0]] = out.get(parts[0], 0.0) + w
    return out


def read_hkcancor(cfg: Config) -> list[list[str]]:
    path = cfg.src("hkcancor")
    sents: list[list[str]] = []
    if not path.exists():
        return sents
    with zipfile.ZipFile(path) as z:
        for name in sorted(z.namelist()):
            if "/data/utf8/" not in name or name.endswith("/"):
                continue
            text = z.read(name).decode("utf-8", "replace")
            for block in re.findall(r"<sent_tag>(.*?)</sent_tag>", text, re.S):
                toks = []
                for line in block.splitlines():
                    line = line.strip()
                    if line:
                        toks.append(line.split("/")[0])
                if toks:
                    sents.append(toks)
    return sents


def read_ud(cfg: Config) -> list[list[str]]:
    path = cfg.src("ud_cantonese_hk")
    sents: list[list[str]] = []
    if not path.exists():
        return sents
    cur: list[str] = []
    for line in path.read_text("utf-8").splitlines():
        if not line.strip():
            if cur:
                sents.append(cur)
            cur = []
            continue
        if line.startswith("#"):
            continue
        cols = line.split("\t")
        if len(cols) > 1 and cols[0].isdigit():
            cur.append(cols[1])
    if cur:
        sents.append(cur)
    return sents


def read_tatoeba(cfg: Config) -> list[str]:
    out = []
    for line in (cfg.refs / "yue_sentences.tsv").read_text("utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            out.append(unicodedata.normalize("NFKC", parts[2]))
    return out


def segment(text: str, logp: dict[str, float], max_len: int, floor: float) -> list[str]:
    """Maximum-likelihood segmentation over the headword lexicon (TXT-012 style)."""
    out: list[str] = []
    for run in re.split(r"([^㐀-鿿\U00020000-\U0003FFFF]+)", text):
        if not run:
            continue
        if not is_hanzi(run[0]):
            out.append(run)
            continue
        n = len(run)
        best = [0.0] + [-1e18] * n
        back = [0] * (n + 1)
        for i in range(n):
            if best[i] <= -1e17:
                continue
            for j in range(i + 1, min(n, i + max_len) + 1):
                w = run[i:j]
                lp = logp.get(w)
                if lp is None:
                    if j > i + 1:
                        continue
                    lp = floor
                if best[i] + lp > best[j]:
                    best[j] = best[i] + lp
                    back[j] = i
        cut = []
        j = n
        while j > 0:
            cut.append(run[back[j]:j])
            j = back[j]
        out.extend(reversed(cut))
    return out


def build_frequency(ds, cfg: Config) -> dict:
    """Sets ds.freq / ds.total_freq; returns corpus material for the LM."""
    heads = {e["trad"] for e in ds.entries if e["segmentable"] and not e["unihan"]}
    essay = read_essay(cfg)
    hk = read_hkcancor(cfg)
    ud = read_ud(cfg)
    tat = [] if cfg.fixture else read_tatoeba(cfg)
    scale = ESSAY_TOTAL / max(1.0, sum(essay.values()))
    base: dict[str, float] = {w: v * scale for w, v in essay.items()}
    pre = Counter()
    for name, sents in (("hkcancor", hk), ("ud_cantonese_hk", ud)):
        for s in sents:
            for w in s:
                pre[w] += WEIGHTS[name]
    for w, c in pre.items():
        base[w] = base.get(w, 0.0) + c
    # segment Tatoeba with the lexicon and the frequencies so far
    total0 = max(1.0, sum(base.values()))
    seg_logp = {w: math.log(max(base.get(w, 0.0), 0.5) / total0) for w in heads if len(w) <= 8}
    floor = math.log(0.1 / total0)
    tat_sents = [segment(t, seg_logp, 8, floor) for t in tat]
    for s in tat_sents:
        for w in s:
            base[w] = base.get(w, 0.0) + WEIGHTS["tatoeba_yue"]
    total = int(round(sum(base.values())))
    ds.freq = {w: max(1, int(round(c))) for w, c in base.items() if w in heads and c >= 0.5}
    ds.total_freq = total
    if not ds.freq:
        raise RuntimeError("Cantonese frequency table is empty (DATA-114): corpus sources missing")
    sources = []
    if essay:
        sources.append(_lock_source(cfg, "rime_essay_cantonese", "rime-cantonese essay-cantonese.txt", "unigram base"))
    if hk:
        sources.append(_lock_source(cfg, "hkcancor", "Hong Kong Cantonese Corpus (HKCanCor)", "counts and bigrams"))
    if ud:
        sources.append(_lock_source(cfg, "ud_cantonese_hk", "UD_Cantonese-HK", "counts and bigrams"))
    if tat:
        sources.append({"name": "Tatoeba yue sentences", "version": "refs/yue_sentences.tsv", "licence": "CC-BY-2.0-FR",
                        "url": "https://tatoeba.org", "role": "counts and bigrams"})
    report = {"essay_words": len(essay), "essay_rescaled_total": ESSAY_TOTAL if essay else 0,
              "hkcancor_sentences": len(hk), "hkcancor_tokens": sum(map(len, hk)),
              "ud_sentences": len(ud), "tatoeba_sentences": len(tat), "tatoeba_tokens": sum(map(len, tat_sents)),
              "weights": WEIGHTS, "table_forms": len(ds.freq), "total": total,
              "skipped_sources": ["Common Voice yue (login-gated download)", "zh-yue Wikipedia dump (size / effort)"]}
    return {"sentences": hk + ud + tat_sents, "report": report, "sources": sources,
            "lm_source": [dict(s) for s in sources]}


def cantonese_lm(ds, cfg: Config, corpus: dict):
    total = max(1, ds.total_freq)
    uni = {w: (math.log(c / total), 0.0) for w, c in ds.freq.items()}
    lm = lmmod.build_vocab(ds, cfg, uni)
    wid = lm.word_id
    left = Counter()
    pairs: Counter = Counter()
    for sent in corpus["sentences"]:
        prev = None
        for w in sent:
            cur = wid.get(w) if w not in ("佢嘅", "我嘅", "你嘅") else None
            if prev is not None and cur is not None:
                pairs[(prev, cur)] += 1
                left[prev] += 1
            prev = cur
    types = Counter(a for (a, _b), c in pairs.items())
    backoffs: dict[str, float] = {}
    lam: dict[int, float] = {}
    for a, n in left.items():
        lam[a] = max(1e-4, min(0.999, DISCOUNT * types[a] / n))
        backoffs[lm.words[a]] = math.log(lam[a])

    def gen():
        for (a, b), c in sorted(pairs.items()):
            if c < MIN_BIGRAM_COUNT:
                continue
            p = (c - DISCOUNT) / left[a] + lam[a] * math.exp(lm.logp[b])
            yield a, b, math.log(p)

    lmmod.add_bigrams(lm, cfg, gen(), backoffs)
    lm.report["corpus_bigram_types"] = len(pairs)
    lm.report["method"] = (f"unigrams from the Cantonese frequency table; bigrams from segmented corpora, "
                           f"absolute discounting D={DISCOUNT} interpolated with the unigram, pairs seen >= {MIN_BIGRAM_COUNT}")
    return lm
