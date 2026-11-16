"""Language-model companion build (PIPE-070…076, DATA-150…155)."""
from __future__ import annotations

import bisect
import heapq
import math
import os
import re
import shutil
import subprocess
import tarfile
from collections import defaultdict
from pathlib import Path

from .config import Config
from .core import LM_DDL, RSRC, Dataset, finish, open_new
from .enc import varint
from .roman import key_syllables

LN10 = math.log(10.0)


# ------------------------------------------------------------------------------------------
# libime ARPA (PIPE-071)

def _zstd_stream(path: Path):
    try:
        from compression import zstd  # Python 3.14+
        return zstd.open(path, "rb"), None
    except ImportError:
        exe = shutil.which("zstd")
        if not exe:
            raise RuntimeError("need Python 3.14+ (compression.zstd) or the `zstd` command to read the libime model")
        proc = subprocess.Popen([exe, "-dc", str(path)], stdout=subprocess.PIPE)
        return proc.stdout, proc


def extract_arpa(cfg: Config) -> Path:
    """Extract lm_sc.arpa (all three orders) from the libime archive into a cache file."""
    archive = cfg.src("libime_lm_sc")
    if archive.name.endswith(".arpa"):
        return archive
    cache_dir = cfg.sources / "cache"
    cache = cache_dir / (archive.name + ".1-3grams.arpa")
    if cache.exists():
        return cache
    cache_dir.mkdir(parents=True, exist_ok=True)
    inner = None
    if archive.name.endswith(".tar.zst"):
        inner = archive
    else:
        with tarfile.open(archive, "r|*") as tf:
            for m in tf:
                if re.search(r"lm_sc\.arpa[^/]*\.tar\.zst$", m.name):
                    inner = cache_dir / os.path.basename(m.name)
                    with open(inner, "wb") as out:
                        shutil.copyfileobj(tf.extractfile(m), out, 1 << 20)
                    break
    if inner is None:
        raise RuntimeError(f"no lm_sc.arpa archive inside {archive}")
    stream, proc = _zstd_stream(inner)
    tmp = cache.with_suffix(".part")
    done = False
    with tarfile.open(fileobj=stream, mode="r|") as tf, open(tmp, "wb") as out:
        for m in tf:
            if not m.name.endswith(".arpa"):
                continue
            shutil.copyfileobj(tf.extractfile(m), out, 1 << 20)
            done = True
            break
    if proc is not None:
        proc.kill()
    if not done:
        tmp.unlink(missing_ok=True)
        raise RuntimeError("no .arpa file in the libime model archive")
    tmp.replace(cache)
    return cache


def read_arpa_unigrams(path: Path) -> dict[str, tuple[float, float]]:
    uni: dict[str, tuple[float, float]] = {}
    section = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("\\"):
                section = 1 if line.startswith("\\1-grams") else (2 if line.startswith("\\2-grams") else 0)
                if section == 2:
                    break
                continue
            if section != 1 or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                raise ValueError(f"malformed ARPA unigram line: {line!r}")
            uni[parts[1]] = (float(parts[0]) * LN10, float(parts[2]) * LN10 if len(parts) > 2 else 0.0)
    return uni


def iter_arpa_bigrams(path: Path, vocab: dict[str, int]):
    section = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line[0] == "\\":
                if line.startswith("\\3-grams"):
                    break
                section = 2 if line.startswith("\\2-grams") else 0
                continue
            if section != 2:
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            a, _, b = parts[1].rstrip("\n").partition(" ")
            ia = vocab.get(a)
            if ia is None:
                continue
            ib = vocab.get(b)
            if ib is None:
                continue
            yield ia, ib, float(parts[0]) * LN10


# ------------------------------------------------------------------------------------------
# Quantisation (DATA-154)

def build_codebook(values: list[float], k: int = 256) -> list[float]:
    """At most `k` ascending natural-log values. A uniform grid in log space over [max(min, -30), max]
    (frequent words keep their resolution: ~0.1 nat), plus a few coarse levels for the smoothed tail."""
    if not values:
        return [0.0]
    distinct = sorted(set(round(v, 4) for v in values))
    if len(distinct) <= k:
        return distinct
    lo, hi = min(values), max(values)
    tail = [v for v in (-60.0, -50.0, -42.0, -36.0, -32.0) if v > lo - 5 and v < -30.0]
    start = max(lo, -30.0)
    n = k - len(tail)
    step = (hi - start) / (n - 1)
    grid = [round(start + i * step, 4) for i in range(n)]
    return sorted(set(tail + grid))


class Quantiser:
    def __init__(self, values: list[float]):
        self.cents = build_codebook(values)
        self.mids = [(self.cents[i] + self.cents[i + 1]) / 2 for i in range(len(self.cents) - 1)]

    def code(self, v: float) -> int:
        return bisect.bisect_left(self.mids, v)


# ------------------------------------------------------------------------------------------

class LM:
    def __init__(self) -> None:
        self.words: list[str] = []
        self.word_id: dict[str, int] = {}
        self.logp: list[float] = []
        self.observed: set[str] = set()
        self.q_uni: Quantiser | None = None
        self.q_bi: Quantiser | None = None
        self.q_bo: Quantiser | None = None
        self.bigrams: dict[int, list[tuple[int, float]]] = {}
        self.backoff: dict[int, float] = {}
        self.abbr: dict[str, list[tuple]] = {}
        self.report: dict = {}
        self.has_bigrams = False
        # combination model (PIPE-087): a lm_ngram.NgramModel, or None
        self.ngram = None


def lm_form(ds: Dataset) -> str:
    return "simp" if ds.id == "mandarin" else "trad"


def build_vocab(ds: Dataset, cfg: Config, unigrams: dict[str, tuple[float, float]]) -> LM:
    """DATA-152 / PIPE-072: dense ids in descending unigram order; smoothed unigrams for OOV headwords."""
    lm = LM()
    field = lm_form(ds)
    forms = sorted({e[field] for e in ds.entries if not e["unihan"]})
    floor = min((lp for lp, _bo in unigrams.values()), default=-20.0) - 1.0
    logp: dict[str, float] = {}
    for w in forms:
        hit = unigrams.get(w)
        if hit is not None:
            logp[w] = hit[0]
            lm.observed.add(w)
        else:
            # PIPE-072 smoothing: a headword no corpus observed sits below every observed word — a flat
            # value for multi-character words (so a dictionary word still beats a split into rare single
            # characters, whatever its length) and a lower one for a single character.
            logp[w] = floor + cfg.oov_penalty * (0.25 if len(w) > 1 else 1.0)
    order = sorted(forms, key=lambda w: (-logp[w], w))
    lm.words = order
    lm.word_id = {w: i for i, w in enumerate(order)}
    lm.logp = [logp[w] for w in order]
    lm.q_uni = Quantiser(lm.logp)
    lm.report.update({
        "lm_words": len(order), "observed_unigrams": len(lm.observed),
        "observed_share": round(len(lm.observed) / max(1, len(order)), 4),
        "smoothing": {"method": "flat: floor + 0.25 * oov_penalty for multi-character words, floor + oov_penalty for single characters (floor = lowest observed unigram - 1)",
                      "oov_penalty": cfg.oov_penalty, "char_floor": round(floor, 3)}})
    return lm


def add_bigrams(lm: LM, cfg: Config, bigram_iter, backoffs: dict[str, float], total_seen: list[int] | None = None) -> None:
    """PIPE-073: keep headword pairs, drop those near the backoff estimate, cap per left word."""
    logp = lm.logp
    words = lm.words
    bo = [backoffs.get(w, 0.0) for w in words]
    seen = kept = 0

    def scored():
        nonlocal seen, kept
        for a, b, lp in bigram_iter:
            seen += 1
            gain = lp - (bo[a] + logp[b])
            if gain < cfg.lm_bigram_min_gain:
                continue
            kept += 1
            yield (math.exp(logp[a] + lp) * gain, a, b, lp)

    top = heapq.nlargest(int(cfg.lm_bigram_target * 1.3), scored())
    per_left: dict[int, list[tuple[float, int, float]]] = defaultdict(list)
    for score, a, b, lp in top:
        per_left[a].append((score, b, lp))
    flat = []
    for a, lst in per_left.items():
        lst.sort(reverse=True)
        for score, b, lp in lst[:cfg.lm_bigram_cap_per_left]:
            flat.append((score, a, b, lp))
    flat.sort(reverse=True)
    flat = flat[:cfg.lm_bigram_target]
    out: dict[int, list[tuple[int, float]]] = defaultdict(list)
    for _score, a, b, lp in flat:
        out[a].append((b, lp))
    for a in out:
        out[a].sort()
    lm.bigrams = dict(out)
    lm.backoff = {a: bo[a] for a in out}
    lm.has_bigrams = bool(out)
    lm.q_bi = Quantiser([lp for lst in out.values() for _b, lp in lst])
    lm.q_bo = Quantiser(list(lm.backoff.values()))
    lm.report.update({"bigrams_headword_pairs": seen, "bigrams_above_min_gain": kept,
                      "bigrams_kept": len(flat), "bigram_left_words": len(out),
                      "config": {"target": cfg.lm_bigram_target, "cap_per_left": cfg.lm_bigram_cap_per_left,
                                 "min_gain_nats": cfg.lm_bigram_min_gain}})


def build_abbr(ds: Dataset, lm: LM, cfg: Config) -> None:
    """DATA-151 first-letter index."""
    field = lm_form(ds)
    syl_idx = {s: i for i, s in enumerate(ds.syllables)}
    post: dict[str, list[tuple]] = defaultdict(list)
    for e in ds.entries:
        if e["unihan"] or e["rsrc"] == RSRC["none"]:
            continue
        wid = lm.word_id[e[field]]
        lp = lm.logp[wid]
        code = lm.q_uni.code(lp)
        variants = []
        if e.get("full_parse"):
            variants.append((0, key_syllables(e["syllables"])))
        for _key, alt, _ord, _tones, syl in e["alt_rows"]:
            variants.append((alt, syl))
        for alt, syl in variants:
            if not 1 <= len(syl) <= 6 or any(s not in syl_idx for s in syl):
                continue
            key = "".join(s[0] for s in syl)
            if not key.isascii() or not key.isalpha():
                continue
            # unigram order; among the entries of one word, its primary reading first (SCHEMA note 18)
            post[key].append((-lp, wid, e.get("rrank", 0), e["id"], code, alt, tuple(syl_idx[s] for s in syl)))
    capped = 0
    total = 0
    for key, lst in post.items():
        lst.sort()
        cap = cfg.abbr_cap_single if len(key) == 1 else (cfg.abbr_cap_two if len(key) == 2 else cfg.abbr_cap_long)
        if len(lst) > cap:
            capped += 1
            del lst[cap:]
        total += len(lst)
    lm.abbr = dict(post)
    lm.report.update({"abbr_keys": len(post), "abbr_postings": total, "abbr_capped_keys": capped,
                      "abbr_caps": {"single": cfg.abbr_cap_single, "two": cfg.abbr_cap_two, "long": cfg.abbr_cap_long}})


_UNSET = object()


def write_lm(ds: Dataset, lm: LM, path: Path, ngram=_UNSET) -> int:
    """Writes the companion file; returns its size. `ngram` (a lm_ngram.Model, default the one of
    `lm.ngram`) adds the combination tables (DATA-156)."""
    from . import lm_ngram
    if ngram is _UNSET:
        ngram = lm.ngram.model if lm.ngram is not None else None
    field = lm_form(ds)
    con = open_new(path, LM_DDL)
    meta = {"dataset_id": ds.id, "dataset_version": ds.meta.get("dataset_version", ""),
            "lm_word_count": str(len(lm.words)), "schema_version": ds.meta.get("schema_version", "")}
    if ngram is not None:
        meta["has_trigram_model"] = "1"
    con.executemany("INSERT INTO meta VALUES(?,?)", sorted(meta.items()))
    rows = []
    for e in ds.entries:
        if e["unihan"]:
            rows.append((e["id"], -1, 0))
        else:
            wid = lm.word_id[e[field]]
            rows.append((e["id"], wid, lm.q_uni.code(lm.logp[wid])))
    con.executemany("INSERT INTO lm_entry VALUES(?,?,?)", rows)
    for kind, q in ((0, lm.q_uni), (1, lm.q_bi), (2, lm.q_bo)):
        if q is not None:
            con.executemany("INSERT INTO lm_codebook VALUES(?,?,?)", ((kind, i, v) for i, v in enumerate(q.cents)))
    abbr_bytes = 0
    for key in sorted(lm.abbr):
        buf = bytearray(varint(len(lm.abbr[key])))
        for _neg, wid, _rr, eid, code, alt, syl in lm.abbr[key]:
            buf += varint(eid) + varint(wid) + bytes((code, alt, len(syl)))
            for s in syl:
                buf += varint(s)
        abbr_bytes += len(buf)
        con.execute("INSERT INTO abbr_idx VALUES(?,?)", (key, bytes(buf)))
    bi_bytes = 0
    for left in sorted(lm.bigrams):
        succ = lm.bigrams[left]
        buf = bytearray((lm.q_bo.code(lm.backoff[left]),))
        buf += varint(len(succ))
        prev = 0
        for i, (b, _lp) in enumerate(succ):
            buf += varint(b if i == 0 else b - prev)
            prev = b
        buf += bytes(lm.q_bi.code(lp) for _b, lp in succ)
        bi_bytes += len(buf)
        con.execute("INSERT INTO bigram VALUES(?,?)", (left, bytes(buf)))
    ng_info = lm_ngram.write_tables(con, ngram) if ngram is not None else None
    finish(con)
    size = os.path.getsize(path)
    if ngram is not None and (lm.ngram is None or ngram is not lm.ngram.model):
        return size                                  # a trial write while pruning to the byte budget
    if ng_info is not None:
        lm.report["combination_model"] = {**lm.ngram.report, "tables": ng_info}
    lm.report.update({"abbr_idx_bytes": abbr_bytes, "bigram_bytes": bi_bytes, "file_size": os.path.getsize(path),
                      "codebook_sizes": {"unigram": len(lm.q_uni.cents),
                                         "bigram": len(lm.q_bi.cents) if lm.q_bi else 0,
                                         "backoff": len(lm.q_bo.cents) if lm.q_bo else 0}})
    return size


# ------------------------------------------------------------------------------------------
# Reference decoder used by the quality gate (PIPE-075). Scores with the quantised values, like the app.

def decode_abbr(ds: Dataset, lm: LM, query: str, top_edges: int = 40, beam: int = 60, nbest: int = 5):
    """Best word sequences for a pure first-letter query, e.g. 'wxhn' -> [我, 喜欢, 你]."""
    field = lm_form(ds)
    n = len(query)
    uni = lambda wid: lm.q_uni.cents[lm.q_uni.code(lm.logp[wid])]      # noqa: E731

    def cond(prev: int | None, wid: int) -> float:
        if prev is not None and prev in lm.bigrams:
            for b, lp in lm.bigrams[prev]:
                if b == wid:
                    return lm.q_bi.cents[lm.q_bi.code(lp)]
            return lm.q_bo.cents[lm.q_bo.code(lm.backoff[prev])] + uni(wid)
        return uni(wid)

    edges: dict[int, list[tuple[int, int, int]]] = defaultdict(list)   # start -> (end, wid, eid)
    for i in range(n):
        for j in range(i + 1, min(n, i + 6) + 1):
            seen = set()
            for _neg, wid, _rr, eid, _code, _alt, _syl in lm.abbr.get(query[i:j], ()):
                if wid in seen:
                    continue
                seen.add(wid)
                edges[i].append((j, wid, eid))
                if len(seen) >= top_edges:
                    break
    states: list[dict[int | None, tuple[float, tuple]]] = [dict() for _ in range(n + 1)]
    states[0][None] = (0.0, ())
    for i in range(n):
        cur = sorted(states[i].items(), key=lambda kv: -kv[1][0])[:beam]
        for prev, (score, path) in cur:
            for j, wid, eid in edges.get(i, ()):
                s = score + cond(prev, wid)
                old = states[j].get(wid)
                if old is None or s > old[0]:
                    states[j][wid] = (s, path + (eid,))
    final = sorted(states[n].values(), key=lambda v: -v[0])[:nbest]
    return [([ds.entries[e][field] for e in path], round(score, 3)) for score, path in final]


def segment_lattice(ds: Dataset, lm: LM, head: dict[str, set[int]], text: str, max_len: int = 12,
                    word_penalty: float = 1.0, unknown: float = -20.0) -> list[str]:
    """Reference of the app's passage segmentation (TXT-012 / TXT-026, SCHEMA note 20): best path through
    the character lattice of `text` (one run) with the quantised unigram / bigram / backoff values.
    `head` maps a headword key to entry ids."""
    field = lm_form(ds)
    n = len(text)
    uni = lambda wid: lm.q_uni.cents[lm.q_uni.code(lm.logp[wid])]      # noqa: E731
    succ = {a: {b: lm.q_bi.cents[lm.q_bi.code(lp)] for b, lp in lst} for a, lst in lm.bigrams.items()}
    edges: list[list[tuple[int, int, float]]] = [[] for _ in range(n)]          # start -> (end, word, unigram)
    unmatched = set()                                                           # starts of characters without a headword
    for p in range(n):
        for ln in range(1, min(max_len, n - p) + 1):
            ids = head.get(text[p:p + ln])
            if not ids:
                if ln == 1:
                    edges[p].append((p + 1, -1, unknown))
                    unmatched.add(p)
                continue
            es = [ds.entries[i] for i in sorted(ids)]
            if ln > 1 and not any(e["segmentable"] for e in es):
                continue
            words: list[int] = []
            for e in es:
                if e["unihan"] or (ln > 1 and not e["segmentable"]):
                    continue
                w = lm.word_id[e[field]]
                if w not in words and len(words) < 2:
                    words.append(w)
            if words:
                edges[p] += [(p + ln, w, uni(w)) for w in words]
            else:
                edges[p].append((p + ln, -1, unknown * ln))
    # states[node][last word or ('u', start)] = (score, path)
    states: list[dict] = [dict() for _ in range(n + 1)]
    states[0][None] = (0.0, ())
    for p in range(n):
        for prev, (score, path) in states[p].items():
            block = succ.get(prev) if isinstance(prev, int) else None
            for end, w, u in edges[p]:
                lp = u
                if block is not None and w >= 0:
                    lp = block[w] if w in block else lm.q_bo.cents[lm.q_bo.code(lm.backoff[prev])] + u
                total = score + lp - word_penalty
                key = w if w >= 0 else ("u", p)
                old = states[end].get(key)
                if old is None or total > old[0]:
                    states[end][key] = (total, path + (end,))
    if not states[n]:
        return list(text)
    path = max(states[n].values(), key=lambda v: v[0])[1]
    out, start = [], 0
    merge = False                                   # adjacent unmatched characters form one segment (TXT-073)
    for end in path:
        lone = end - start == 1 and start in unmatched
        if lone and merge:
            out[-1] += text[start:end]
        else:
            out.append(text[start:end])
        merge = lone
        start = end
    return out
