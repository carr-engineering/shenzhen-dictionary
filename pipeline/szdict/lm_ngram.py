"""Combination language model (PIPE-087, DATA-156): bigrams and trigrams over the dictionary
vocabulary for the multi-word combinations (SRCH-062), stored next to the segmentation model of
PIPE-073 (`bigram`, unchanged) in the companion tables `unigram2`, `bigram2` and `trigram`.

1. libime's ARPA model (all three orders) is folded onto dictionary words: a token that is not a
   headword but is all Hanzi is split into headwords by the unigram path (1 nat per word) and its
   probability mass is credited to the words and the n-grams inside and across it; punctuation and
   other tokens are boundaries.
2. Subtitle n-gram counts (PIPE-085, digits read out by PIPE-086) are merged with the folded masses
   (count merging, absolute discount on the subtitle counts).
3. Conditionals N2/N1 and N3/N2 with renormalised Katz-style backoffs; a separate unigram (`u2`, the
   mixed masses) scores the first word and is the backoff distribution.
4. One-shot relative-entropy (Stolcke) pruning of bigrams and trigrams together to a byte budget
   of the companion file, then backoffs renormalised against what is kept.
5. 8-bit uniform log-space codebooks (kinds 3…7)."""
from __future__ import annotations

import hashlib
import math
import os
import re
import sqlite3
from pathlib import Path

import numpy as np

from .config import Config

CODE_VERSION = "1"
LN10 = math.log(10.0)
CLAMP = (math.log(1e-3), math.log(10.0))
TRIGRAM_PAGE = 3900
WORD_BITS = 17
# Measured bytes per kept n-gram in the written file (blocks + SQLite overhead), used to turn the
# pruning costs into a cost per byte; the result is calibrated once against the written file.
BYTES_BIGRAM = 2.9
BYTES_TRIGRAM = 4.6

LM2_DDL = """
CREATE TABLE unigram2(block INTEGER PRIMARY KEY, codes BLOB);
CREATE TABLE bigram2(left INTEGER PRIMARY KEY, block BLOB);
CREATE TABLE trigram(key INTEGER PRIMARY KEY, block BLOB);
"""


# ------------------------------------------------------------------------------------------
# ARPA as arrays

def arpa_arrays(path: Path, cache_dir: Path | None) -> dict:
    """Every token of the ARPA file and its n-grams as arrays (natural logs); cached by file."""
    st = path.stat()
    cache = None
    if cache_dir is not None:
        key = hashlib.sha256(f"{CODE_VERSION}|{path.name}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:16]
        cache = cache_dir / f"arpa-arrays-{key}.npz"
        if cache.exists():
            z = np.load(cache, allow_pickle=False)
            out = {k: z[k] for k in z.files}
            out["names"] = list(out["names"])
            return out
    tok: dict[str, int] = {}
    names: list[str] = []
    ulp: list[float] = []
    ba: list[int] = []
    bb: list[int] = []
    blp: list[float] = []
    ta: list[int] = []
    tb: list[int] = []
    tc: list[int] = []
    tlp: list[float] = []
    section = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line[0] == "\\":
                section = {"\\1": 1, "\\2": 2, "\\3": 3}.get(line[:2], 0)
                continue
            if section == 0 or len(line) < 3:
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) < 2:
                continue
            if section == 1:
                tok[p[1]] = len(names)
                names.append(p[1])
                ulp.append(float(p[0]))
            elif section == 2:
                a, b = p[1].split(" ")
                ba.append(tok[a])
                bb.append(tok[b])
                blp.append(float(p[0]))
            else:
                a, b, c = p[1].split(" ")
                ta.append(tok[a])
                tb.append(tok[b])
                tc.append(tok[c])
                tlp.append(float(p[0]))
    f64 = lambda x: np.array(x, np.float64) * LN10     # noqa: E731
    i32 = lambda x: np.array(x, np.int32)              # noqa: E731
    out = {"ulp": f64(ulp), "ba": i32(ba), "bb": i32(bb), "blp": f64(blp),
           "ta": i32(ta), "tb": i32(tb), "tc": i32(tc), "tlp": f64(tlp)}
    if cache is not None:
        cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = cache.with_suffix(".part.npz")
        np.savez(tmp, names=np.array(names), **out)
        tmp.replace(cache)
    out["names"] = names
    return out


# ------------------------------------------------------------------------------------------
# Masses

def _agg(keys: np.ndarray, mass: np.ndarray):
    k, inv = np.unique(keys, return_inverse=True)
    return k, np.bincount(inv, weights=mass, minlength=len(k))


def _has(keys: np.ndarray, sorted_keys: np.ndarray) -> np.ndarray:
    if len(sorted_keys) == 0:
        return np.zeros(len(keys), bool)
    pos = np.minimum(np.searchsorted(sorted_keys, keys), len(sorted_keys) - 1)
    return sorted_keys[pos] == keys


def _lookup(keys: np.ndarray, sk: np.ndarray, sv: np.ndarray, default=np.nan) -> np.ndarray:
    if len(sk) == 0:
        return np.full(len(keys), default)
    pos = np.minimum(np.searchsorted(sk, keys), len(sk) - 1)
    return np.where(sk[pos] == keys, sv[pos], default)


class Masses:
    """Joint masses N1[V], N2 (keys a*V+b), N3 (keys (a*V+b)*V+c) over LM word ids."""

    def __init__(self, V: int, N1, k2, m2, k3, m3):
        self.V, self.N1, self.k2, self.m2, self.k3, self.m3 = V, N1, k2, m2, k3, m3


def segment_word(w: str, wid: dict[str, int], logp: list[float], max_len: int = 12) -> tuple | None:
    """Best split of an all-Hanzi token into LM words (unigram + 1 nat per word), or None."""
    n = len(w)
    best: list = [(-1e18, None)] * (n + 1)
    best[0] = (0.0, ())
    for p in range(n):
        if best[p][1] is None:
            continue
        for q in range(p + 1, min(n, p + max_len) + 1):
            j = wid.get(w[p:q])
            if j is None:
                continue
            sc = best[p][0] + logp[j] - 1.0
            if sc > best[q][0]:
                best[q] = (sc, best[p][1] + (j,))
    return best[n][1]


def fold_libime(arpa: dict, words: list[str], logp: list[float]) -> tuple[Masses, dict]:
    """PIPE-087 step 1: libime's joint masses folded onto dictionary words."""
    V = len(words)
    wid = {w: i for i, w in enumerate(words)}
    names = arpa["names"]
    T = len(names)
    nexp = np.zeros(T, np.int32)
    first = np.full(T, -1, np.int64)
    second = first.copy()
    last = first.copy()
    pen = first.copy()
    exps: dict[int, tuple] = {}
    han = re.compile(r"^[㐀-鿿]+$")
    nondict = decomposed = 0
    for t, w in enumerate(names):
        i = wid.get(w)
        if i is not None:
            e = (i,)
        elif han.match(w):
            nondict += 1
            e = segment_word(w, wid, logp)
            if e is None:
                continue
            decomposed += 1
            exps[t] = e
        else:
            continue
        nexp[t] = len(e)
        first[t] = e[0]
        last[t] = e[-1]
        if len(e) > 1:
            second[t] = e[1]
            pen[t] = e[-2]
    p = np.exp(arpa["ulp"])
    ok = nexp > 0
    N1 = np.zeros(V)
    one = ok & (nexp == 1)
    np.add.at(N1, first[one], p[one])
    k2l: list[int] = []
    m2l: list[float] = []
    k3l: list[int] = []
    m3l: list[float] = []
    for t, e in exps.items():
        for x in e:
            N1[x] += p[t]
        for i in range(len(e) - 1):
            k2l.append(e[i] * V + e[i + 1])
            m2l.append(p[t])
        for i in range(len(e) - 2):
            k3l.append((e[i] * V + e[i + 1]) * V + e[i + 2])
            m3l.append(p[t])
    ba, bb = arpa["ba"].astype(np.int64), arpa["bb"].astype(np.int64)
    J2 = p[ba] * np.exp(arpa["blp"])
    okb = ok[ba] & ok[bb]
    k2 = np.concatenate([np.array(k2l, np.int64), last[ba[okb]] * V + first[bb[okb]]])
    m2 = np.concatenate([np.array(m2l, np.float64), J2[okb]])
    s = okb & (nexp[ba] >= 2)
    ka = (pen[ba[s]] * V + last[ba[s]]) * V + first[bb[s]]
    s2 = okb & (nexp[bb] >= 2)
    kb = (last[ba[s2]] * V + first[bb[s2]]) * V + second[bb[s2]]
    ta, tb, tc = arpa["ta"].astype(np.int64), arpa["tb"].astype(np.int64), arpa["tc"].astype(np.int64)
    tokkey = ba * T + bb
    order = np.argsort(tokkey, kind="stable")
    tk = tokkey[order]
    want = ta * T + tb
    found = _has(want, tk)
    pos = np.minimum(np.searchsorted(tk, want), max(len(tk) - 1, 0))
    J3 = (np.where(found, J2[order][pos], 0.0) * np.exp(arpa["tlp"])) if len(tk) else np.zeros(len(ta))
    okt = found & ok[ta] & ok[tc] & (nexp[tb] == 1)
    kc = (last[ta[okt]] * V + first[tb[okt]]) * V + first[tc[okt]]
    k3 = np.concatenate([np.array(k3l, np.int64), ka, kb, kc])
    m3 = np.concatenate([np.array(m3l, np.float64), J2[s], J2[s2], J3[okt]])
    k2, m2 = _agg(k2, m2)
    k3, m3 = _agg(k3, m3)
    has = _has(k3 // V, k2)
    k3, m3 = k3[has], m3[has]
    total = float(p.sum())
    stats = {"arpa_tokens": T, "arpa_bigrams": len(ba), "arpa_trigrams": len(ta),
             "nonheadword_hanzi_tokens": nondict, "nonheadword_tokens_decomposed": decomposed,
             "usable_mass_share": round(float(p[ok].sum()) / total, 4) if total else 0.0,
             "headword_mass_share": round(float(p[np.array([n in wid for n in names], bool)].sum()) / total, 4) if total else 0.0,
             "trigram_prefix_missing": int((~found).sum()),
             "folded_bigrams": len(k2), "folded_trigrams": len(k3)}
    return Masses(V, N1, k2, m2, k3, m3), stats


def mix(lib: Masses, subs: dict, lam: float, D: float) -> Masses:
    """PIPE-087 step 2: lam * discounted subtitle counts + (1 - lam) * folded libime masses."""
    V = lib.V
    sl = float(lib.N1.sum()) or 1.0
    T = float(subs["c1"].sum()) or 1.0
    N1 = lam * subs["c1"] / T + (1 - lam) * lib.N1 / sl
    k2, m2 = _agg(np.concatenate([subs["k2"], lib.k2]), np.concatenate([lam * (subs["c2"] - D) / T, (1 - lam) * lib.m2 / sl]))
    k3, m3 = _agg(np.concatenate([subs["k3"], lib.k3]), np.concatenate([lam * (subs["c3"] - D) / T, (1 - lam) * lib.m3 / sl]))
    keep = m2 > 0
    k2, m2 = k2[keep], m2[keep]
    has = _has(k3 // V, k2) & (m3 > 0)
    return Masses(V, N1, k2, m2, k3[has], m3[has])


# ------------------------------------------------------------------------------------------
# Model

class Model:
    """uni[V] (u2), bigrams (bk sorted, blp), bo1[V], trigrams (tk sorted, tlp), contexts (ck, bo2)."""

    def __init__(self, V: int, uni: np.ndarray):
        self.V = V
        self.uni = uni
        self.bk = np.zeros(0, np.int64)
        self.blp = np.zeros(0)
        self.bo1 = np.zeros(V)
        self.has1 = np.zeros(V, bool)
        self.tk = np.zeros(0, np.int64)
        self.tlp = np.zeros(0)
        self.ck = np.zeros(0, np.int64)
        self.bo2 = np.zeros(0)

    def bigram_lp(self, a: np.ndarray, b: np.ndarray) -> np.ndarray:
        v = _lookup(a * self.V + b, self.bk, self.blp)
        return np.where(np.isnan(v), self.bo1[a] + self.uni[b], v)

    def trigram_lp(self, a, b, c) -> np.ndarray:
        v = _lookup((a * self.V + b) * self.V + c, self.tk, self.tlp)
        bo = _lookup(a * self.V + b, self.ck, self.bo2, 0.0)
        return np.where(np.isnan(v), bo + self.bigram_lp(b, c), v)


def renorm_bigram(m: Model) -> None:
    V = m.V
    a = m.bk // V
    b = m.bk % V
    m.sp = np.bincount(a, weights=np.exp(m.blp), minlength=V)
    m.su = np.bincount(a, weights=np.exp(m.uni[b]), minlength=V)
    m.has1 = np.bincount(a, minlength=V) > 0
    with np.errstate(divide="ignore", invalid="ignore"):
        bo = np.log(np.maximum(1 - m.sp, 1e-9)) - np.log(np.maximum(1 - m.su, 1e-9))
    m.bo1 = np.where(m.has1, np.clip(bo, *CLAMP), 0.0)


def renorm_trigram(m: Model) -> None:
    V = m.V
    if len(m.tk) == 0:
        m.ck = np.zeros(0, np.int64)
        m.bo2 = np.zeros(0)
        m.tsp = m.tsl = np.zeros(0)
        m.tinv = np.zeros(0, np.int64)
        return
    ctx = m.tk // V
    c = m.tk % V
    b = ctx % V
    m.ck, m.tinv = np.unique(ctx, return_inverse=True)
    m.tsp = np.bincount(m.tinv, weights=np.exp(m.tlp))
    m.tsl = np.bincount(m.tinv, weights=np.exp(m.bigram_lp(b, c)))
    m.bo2 = np.clip(np.log(np.maximum(1 - m.tsp, 1e-9)) - np.log(np.maximum(1 - m.tsl, 1e-9)), *CLAMP)


def unigram2(M: Masses, base: np.ndarray) -> np.ndarray:
    """log N1 of the mixed masses where observed, the shipped unigram elsewhere."""
    with np.errstate(divide="ignore"):
        return np.where(M.N1 > 0, np.log(np.maximum(M.N1, 1e-300)), base)


def full_model(M: Masses, uni: np.ndarray) -> Model:
    V = M.V
    m = Model(V, uni)
    a = M.k2 // V
    m.bk = M.k2
    m.blp = np.minimum(np.log(M.m2) - np.log(M.N1[a]), -1e-4)
    renorm_bigram(m)
    m.tk = M.k3
    m.tlp = np.minimum(np.log(M.m3) - np.log(_lookup(M.k3 // V, M.k2, M.m2)), -1e-4)
    renorm_trigram(m)
    tot = float(M.N1.sum()) or 1.0
    m.hist1 = M.N1 / tot
    m.hist2 = _lookup(m.ck, M.k2, M.m2) / tot
    return m


def stolcke_bigram(m: Model) -> np.ndarray:
    """Relative-entropy cost of removing each bigram (one-shot, as SRILM)."""
    V = m.V
    a = m.bk // V
    b = m.bk % V
    P = np.exp(m.blp)
    U = np.exp(m.uni[b])
    num = np.maximum(1 - m.sp[a], 1e-9)
    den = np.maximum(1 - m.su[a], 1e-9)
    bo = np.log(num) - np.log(den)
    bo2 = np.log(num + P) - np.log(den + U)
    return -m.hist1[a] * (P * (m.uni[b] + bo2 - m.blp) + num * (bo2 - bo))


def stolcke_trigram(m: Model) -> np.ndarray:
    V = m.V
    if len(m.tk) == 0:
        return np.zeros(0)
    c = m.tk % V
    b = (m.tk // V) % V
    P = np.exp(m.tlp)
    lo = m.bigram_lp(b, c)
    L = np.exp(lo)
    num = np.maximum(1 - m.tsp[m.tinv], 1e-9)
    den = np.maximum(1 - m.tsl[m.tinv], 1e-9)
    bo = np.log(num) - np.log(den)
    bo2 = np.log(num + P) - np.log(den + L)
    return -m.hist2[m.tinv] * (P * (lo + bo2 - m.tlp) + num * (bo2 - bo))


def pruned(full: Model, keep_b: np.ndarray, keep_t: np.ndarray) -> Model:
    m = Model(full.V, full.uni)
    m.bk = full.bk[keep_b]
    m.blp = full.blp[keep_b]
    renorm_bigram(m)
    m.tk = full.tk[keep_t]
    m.tlp = full.tlp[keep_t]
    renorm_trigram(m)
    return m


def select(cb: np.ndarray, ct: np.ndarray, budget: float, bb: float, bt: float) -> tuple[np.ndarray, np.ndarray]:
    """Bigrams and trigrams by cost per byte, descending, while their estimated bytes fit `budget`."""
    per = np.concatenate([cb / bb, ct / bt])
    size = np.concatenate([np.full(len(cb), bb), np.full(len(ct), bt)])
    order = np.argsort(-per, kind="stable")
    k = int(np.searchsorted(np.cumsum(size[order]), budget, side="right"))
    keep = np.zeros(len(per), bool)
    keep[order[:k]] = True
    return np.flatnonzero(keep[:len(cb)]), np.flatnonzero(keep[len(cb):])


# ------------------------------------------------------------------------------------------
# Writing (DATA-156)

def codebook(v: np.ndarray, bits: int = 8) -> tuple[np.ndarray, list[float]]:
    """Uniform log-space grid over [max(min, -30), max]; codes round to the nearest level."""
    k = 1 << bits
    if len(v) == 0:
        return np.zeros(0, np.uint8), [0.0]
    lo = max(float(v.min()), -30.0)
    hi = float(v.max())
    if hi <= lo:
        return np.zeros(len(v), np.uint8), [round(lo, 6)]
    step = (hi - lo) / (k - 1)
    codes = np.clip(np.round((v - lo) / step), 0, k - 1).astype(np.uint8)
    return codes, [lo + i * step for i in range(k)]


def _varints(v: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """LEB128 of a non-negative int array (< 2^21): bytes and offsets."""
    v = v.astype(np.int64)
    if len(v) and (v.min() < 0 or v.max() >= 1 << 21):
        raise ValueError("varint out of range")
    n = 1 + (v >= 128) + (v >= 16384)
    off = np.concatenate([[0], np.cumsum(n)]).astype(np.int64)
    out = np.zeros(int(off[-1]), np.uint8)
    out[off[:-1]] = (v & 0x7F) | np.where(n > 1, 0x80, 0)
    m = n > 1
    out[off[:-1][m] + 1] = ((v[m] >> 7) & 0x7F) | np.where(n[m] > 2, 0x80, 0)
    m = n > 2
    out[off[:-1][m] + 2] = (v[m] >> 14) & 0x7F
    return out, off


def _vi(x: int) -> bytes:
    out = bytearray()
    while True:
        b = x & 0x7F
        x >>= 7
        if x:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _runs(keys: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    if len(keys) == 0:
        return np.zeros(0, np.int64), np.zeros(0, np.int64)
    st = np.flatnonzero(np.concatenate([[True], keys[1:] != keys[:-1]]))
    return st, np.concatenate([st[1:], [len(keys)]])


def write_tables(con: sqlite3.Connection, m: Model) -> dict:
    """unigram2, bigram2 and trigram rows and codebooks 3…7 into an open companion file."""
    V = m.V
    if V >= 1 << WORD_BITS:
        raise ValueError(f"{V} LM words do not fit {WORD_BITS} bits")
    for s in LM2_DDL.split(";"):
        if s.strip():
            con.execute(s)
    info: dict = {}
    # unigram2 (kind 7)
    uc, ubook = codebook(m.uni)
    con.executemany("INSERT INTO lm_codebook VALUES(7,?,?)", list(enumerate(ubook)))
    con.executemany("INSERT INTO unigram2 VALUES(?,?)", [(blk, uc[blk << 8:(blk + 1) << 8].tobytes()) for blk in range((V + 255) >> 8)])
    # bigram2 (kinds 3, 4): the layout of `bigram`
    a = m.bk // V
    b = m.bk % V
    bc, bbook = codebook(m.blp)
    lefts = np.flatnonzero(m.has1)
    boc, bobook = codebook(m.bo1[lefts])
    bocode = np.zeros(V, np.uint8)
    bocode[lefts] = boc
    con.executemany("INSERT INTO lm_codebook VALUES(3,?,?)", list(enumerate(bbook)))
    con.executemany("INSERT INTO lm_codebook VALUES(4,?,?)", list(enumerate(bobook)))
    st, en = _runs(a)
    d = b.copy()
    if len(d):
        d[1:] -= b[:-1]
        d[st] = b[st]
    vb, off = _varints(d)
    rows = []
    payload = 0
    for s, e in zip(st.tolist(), en.tolist()):
        left = int(a[s])
        blk = bytes([bocode[left]]) + _vi(e - s) + vb[off[s]:off[e]].tobytes() + bc[s:e].tobytes()
        rows.append((left, blk))
        payload += len(blk)
    con.executemany("INSERT INTO bigram2 VALUES(?,?)", rows)
    info.update({"bigrams": len(m.bk), "bigram_left_words": len(rows), "bigram_bytes": payload})
    # trigram (kinds 5, 6): contexts of one w1 packed into pages
    ctx = m.tk // V
    c = m.tk % V
    tc, tbook = codebook(m.tlp)
    b2c, b2book = codebook(m.bo2)
    con.executemany("INSERT INTO lm_codebook VALUES(5,?,?)", list(enumerate(tbook)))
    con.executemany("INSERT INTO lm_codebook VALUES(6,?,?)", list(enumerate(b2book)))
    st, en = _runs(ctx)
    d = c.copy()
    if len(d):
        d[1:] -= c[:-1]
        d[st] = c[st]
    vb, off = _varints(d)
    ca = (m.ck // V).tolist()
    cbw = (m.ck % V).tolist()
    rows = []
    payload = 0
    cur = bytearray()
    key = 0
    prev_a = -1
    prev_b = 0
    for i, (s, e) in enumerate(zip(st.tolist(), en.tolist())):
        if ca[i] != prev_a or len(cur) >= TRIGRAM_PAGE:
            if cur:
                rows.append((key, bytes(cur)))
                payload += len(cur)
            cur = bytearray()
            key = (ca[i] << WORD_BITS) | cbw[i]
            prev_b = cbw[i]
        cur += _vi(cbw[i] - prev_b) + bytes([b2c[i]]) + _vi(e - s) + vb[off[s]:off[e]].tobytes() + tc[s:e].tobytes()
        prev_a = ca[i]
        prev_b = cbw[i]
    if cur:
        rows.append((key, bytes(cur)))
        payload += len(cur)
    con.executemany("INSERT INTO trigram VALUES(?,?)", rows)
    info.update({"trigrams": len(m.tk), "trigram_contexts": len(m.ck), "trigram_pages": len(rows), "trigram_bytes": payload})
    return info


# ------------------------------------------------------------------------------------------
# Orchestration

class NgramModel:
    """What write_lm needs: the pruned model and its report."""

    def __init__(self, model: Model, report: dict):
        self.model = model
        self.report = report


def build(words: list[str], logp: list[float], arpa_path: Path, subs_path: Path | None, cfg: Config,
          base_file_size, log=print) -> NgramModel:
    """Folds libime, merges the subtitle counts, prunes to `cfg.lm_ngram_budget_bytes` of companion
    file. `base_file_size(model)` writes a companion with the given model and returns its size (bytes);
    it is called to calibrate the byte estimate."""
    from .subs import count_corpus
    cache = None if cfg.fixture else cfg.sources / "cache"
    V = len(words)
    base = np.array(logp, np.float64)
    arpa = arpa_arrays(arpa_path, cache)
    lib, fold = fold_libime(arpa, words, logp)
    del arpa
    log(f"lm: libime folded: {fold['folded_bigrams']} bigrams, {fold['folded_trigrams']} trigrams")
    rep: dict = {"folding": fold}
    if subs_path is not None and subs_path.exists():
        z = count_corpus(subs_path, words, logp, cache, min_bigram=cfg.lm_subs_min_count, min_trigram=cfg.lm_subs_min_count)
        rep["subtitles"] = {"file": subs_path.name, "tokens": int(z["tokens"]), "segments": int(z["segments"]),
                            "bigram_types": len(z["k2"]), "trigram_types": len(z["k3"]),
                            "min_count": cfg.lm_subs_min_count, "weight": cfg.lm_subs_weight, "discount": cfg.lm_subs_discount}
        M = mix(lib, z, cfg.lm_subs_weight, cfg.lm_subs_discount)
        del z
        log(f"lm: subtitles {rep['subtitles']['tokens']} tokens; mixed: {len(M.k2)} bigrams, {len(M.k3)} trigrams")
    else:
        rep["subtitles"] = None
        M = lib
    del lib
    u2 = unigram2(M, base)
    full = full_model(M, u2)
    rep["mixed"] = {"bigrams": len(full.bk), "trigrams": len(full.tk), "contexts": len(full.ck)}
    del M
    cb = stolcke_bigram(full)
    ct = stolcke_trigram(full)
    budget = cfg.lm_ngram_budget_bytes
    empty = pruned(full, np.zeros(0, np.int64), np.zeros(0, np.int64))
    base_size = base_file_size(empty)
    bb, bt = BYTES_BIGRAM, BYTES_TRIGRAM
    kb, kt = select(cb, ct, budget - base_size, bb, bt)
    m = pruned(full, kb, kt)
    size = base_file_size(m)
    if len(kb) + len(kt) < len(cb) + len(ct) and abs(size - budget) > 0.02 * budget and size > base_size:
        scale = (size - base_size) / max(1.0, budget - base_size)
        bb, bt = bb * scale, bt * scale
        kb, kt = select(cb, ct, budget - base_size, bb, bt)
        m = pruned(full, kb, kt)
        size = base_file_size(m)
    rep["pruning"] = {"method": "Stolcke relative entropy, bigrams and trigrams together, by cost per byte",
                      "budget_bytes": budget, "base_bytes": base_size, "bytes_per_bigram": round(bb, 3),
                      "bytes_per_trigram": round(bt, 3), "kept_bigrams": int(len(kb)), "kept_trigrams": int(len(kt)),
                      "file_bytes": size}
    log(f"lm: pruned to {len(kb)} bigrams, {len(kt)} trigrams, {size} bytes")
    return NgramModel(m, rep)
