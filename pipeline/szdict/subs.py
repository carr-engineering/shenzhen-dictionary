"""Spoken-register n-gram counts from a subtitle corpus (PIPE-085): every line is normalised
(digits as they are read, PIPE-086), cut into Hanzi runs and segmented with the dictionary's own
vocabulary, so every counted token is a language-model word."""
from __future__ import annotations

import gzip
import hashlib
import multiprocessing as mp
import re
from pathlib import Path

import numpy as np

from .textnorm import read_digits

CODE_VERSION = "1"
_RUN = re.compile(r"[㐀-鿿]+")
_G: dict = {}


def _segment_run(run: str, vocab: dict, prefixes: set, out: list) -> None:
    """Best unigram path with 1 nat per word (as TXT-012); a character outside the vocabulary ends the run."""
    n = len(run)
    best = [0.0] + [-1e18] * n
    back = [0] * (n + 1)
    word = [-1] * (n + 1)
    for i in range(n):
        if best[i] <= -1e17:
            continue
        j = i + 1
        while j <= n:
            w = run[i:j]
            hit = vocab.get(w)
            if hit is not None:
                s = best[i] + hit[1] - 1.0
                if s > best[j]:
                    best[j] = s
                    back[j] = i
                    word[j] = hit[0]
            elif w not in prefixes:
                break
            j += 1
    if best[n] <= -1e17:
        # a character without a headword: segment the pieces around it
        for piece in _G["known"].findall(run) if len(run) > 1 else ():
            if len(piece) < n:
                _segment_run(piece, vocab, prefixes, out)
        return
    ids = []
    j = n
    while j > 0:
        ids.append(word[j])
        j = back[j]
    out.extend(reversed(ids))
    out.append(-1)


def _work(lines: list[str]) -> np.ndarray:
    vocab, prefixes, norm = _G["vocab"], _G["prefixes"], _G["norm"]
    out: list[int] = []
    for line in lines:
        if norm:
            line = read_digits(line)
        for run in _RUN.findall(line):
            _segment_run(run, vocab, prefixes, out)
    return np.array(out, np.int32)


def _chunks(path: Path, size: int, limit: int | None):
    buf: list[str] = []
    n = 0
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            buf.append(line)
            n += 1
            if len(buf) >= size:
                yield buf
                buf = []
            if limit and n >= limit:
                break
    if buf:
        yield buf


def _ngrams(ids: np.ndarray, V: int, n: int, min_count: int):
    """Sorted keys and counts of the n-grams inside segments (no -1 inside)."""
    if n == 2:
        ok = (ids[:-1] >= 0) & (ids[1:] >= 0)
        keys = ids[:-1][ok].astype(np.int64) * V + ids[1:][ok]
    else:
        ok = (ids[:-2] >= 0) & (ids[1:-1] >= 0) & (ids[2:] >= 0)
        keys = (ids[:-2][ok].astype(np.int64) * V + ids[1:-1][ok]) * V + ids[2:][ok]
    keys.sort()
    if not len(keys):
        return keys, np.zeros(0, np.int64)
    start = np.flatnonzero(np.concatenate([[True], keys[1:] != keys[:-1]]))
    counts = np.diff(np.concatenate([start, [len(keys)]]))
    keep = counts >= min_count
    return keys[start][keep], counts[keep]


def count_corpus(path: Path, words: list[str], logp: list[float], cache_dir: Path | None, normalise: bool = True,
                 min_bigram: int = 2, min_trigram: int = 2, limit: int | None = None, procs: int | None = None) -> dict:
    """Unigram / bigram / trigram counts over LM word ids. Cached by corpus file, vocabulary and options."""
    h = hashlib.sha256()
    st = path.stat()
    h.update(f"{CODE_VERSION}|{path.name}|{st.st_size}|{normalise}|{min_bigram}|{min_trigram}|{limit}|".encode())
    h.update(Path(__file__).with_name("textnorm.py").read_bytes())
    for w, lp in zip(words, logp):
        h.update(f"{w}\t{lp:.3f}\n".encode())
    cache = cache_dir / f"subs-counts-{h.hexdigest()[:16]}.npz" if cache_dir is not None else None
    if cache is not None and cache.exists():
        z = np.load(cache)
        return {k: z[k] for k in z.files}
    V = len(words)
    vocab = {w: (i, lp) for i, (w, lp) in enumerate(zip(words, logp))}
    prefixes = {w[:k] for w in words for k in range(1, len(w))}
    _G.update(vocab=vocab, prefixes=prefixes, norm=normalise,
              known=re.compile("[" + "".join(sorted(w for w in words if len(w) == 1)) + "]+"))
    parts = []
    if st.st_size < 1 << 20:                        # a small excerpt (the fixture) needs no workers
        parts = [_work(chunk) for chunk in _chunks(path, 50_000, limit)]
    else:
        with mp.get_context("fork").Pool(procs or max(1, (mp.cpu_count() or 2) - 1)) as pool:
            for arr in pool.imap(_work, _chunks(path, 50_000, limit)):
                parts.append(arr)
    ids = np.concatenate(parts) if parts else np.zeros(0, np.int32)
    del parts
    c1 = np.bincount(ids[ids >= 0], minlength=V).astype(np.int64)
    k2, c2 = _ngrams(ids, V, 2, min_bigram)
    k3, c3 = _ngrams(ids, V, 3, min_trigram)
    out = {"c1": c1, "k2": k2, "c2": c2, "k3": k3, "c3": c3, "tokens": np.array(int(c1.sum())),
           "segments": np.array(int((ids < 0).sum()))}
    if cache is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        np.savez(cache, **out)
    return out
