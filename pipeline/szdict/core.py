"""Dataset model, shared derivations and the SQLite writer (contract/SCHEMA.md)."""
from __future__ import annotations

import hashlib
import re
import math
import os
import sqlite3
import struct
from collections import defaultdict
from pathlib import Path

from .enc import cjson, delta_list, derive_reading_tokens, plain_list, varint, zjson
from .norm import en_tokens, fold, is_hanzi, matching_key
from .roman import key_syllables, key_tones, reading_key
from .senses import VARIANT_KINDS, labels, plain_text, summarise

SCHEMA_VERSION = 2
UNRANKED = 1_000_000
RSRC = {"cedict": 0, "cccanto": 1, "cccedict_canto": 2, "hsk_patch": 3, "unihan": 4, "none": 5}
BLOCK_LABEL = {"mandarin": "block_mandarin", "cantonese": "block_cantonese",
               "cantonese_adapted": "block_cantonese_adapted", "hsk_patch": "block_hsk_patch",
               "unihan": "block_unihan"}
MAX_WORD_LEN = 12
WITHIN_MAX = 8
SUBSTR_MAX = 4


class BuildError(Exception):
    pass


class Dataset:
    def __init__(self, dataset_id: str):
        self.id = dataset_id
        mand = dataset_id == "mandarin"
        self.language = "cmn" if mand else "yue"
        self.romanization = "pinyin" if mand else "jyutping"
        self.tone_count = 5 if mand else 6
        self.script = "si" if mand else "tr"       # script used for plain display text
        self.entries: list[dict] = []
        self.report: dict = {}
        self.hsk_rows: list[dict] = []
        self.hsk_chars: list[dict] = []
        self.freq: dict[str, int] = {}             # surface form -> count
        self.total_freq = 0
        self.word_freq: dict[str, tuple[int, float]] = {}
        self.merge_groups: list[dict] = []
        self.popular: list[int] = []
        self.radical_forms: dict[str, list[str]] = {}    # radical char -> unified ideographs
        self.syllables: list[str] = []
        self.aliases: dict[str, str] = {}
        self.abbrevs: list[tuple[str, str]] = []
        self.meta: dict[str, str] = {}
        self.sources: list[dict] = []
        self.en_primary: dict | None = None        # cantonese: custom English postings
        self.english_words: dict[str, int] = {}    # common English word forms -> corpus count (SRCH-024)
        self.english_total = 0
        self.body_dict: bytes | None = None        # preset DEFLATE dictionary of the bodies
        self.lm = None

    def add(self, **e) -> dict:
        e.setdefault("senses", [])
        e.setdefault("notes", [])
        e.setdefault("blocks", [])
        e.setdefault("redirect", None)
        e.setdefault("alt", [])
        e.setdefault("alt_rows", [])
        e.setdefault("hsk", [])
        e.setdefault("hsk_via", [])
        e.setdefault("proper", False)
        e.setdefault("unihan", False)
        e.setdefault("segmentable", True)
        e.setdefault("canto", False)
        e.setdefault("freq_rank", UNRANKED)
        e.setdefault("merge_simp", None)
        e.setdefault("merge_trad", None)
        e.setdefault("audio_key", None)
        e["id"] = len(self.entries)
        self.entries.append(e)
        return e


FIXTURE_ENGLISH = {"hen", "sang", "ban", "pan", "fan", "sun", "song", "long", "can", "men", "he", "rent", "render",
                   "cat", "call", "came", "walk", "student", "graduate", "hello"}


def load_english_words(ds: "Dataset", cfg) -> None:
    """Common English word forms with corpus counts (pinned list; SCHEMA note 19)."""
    path = cfg.pinned / "freq" / "en_common.tsv"
    words: dict[str, int] = {}
    total = 0
    for i, line in enumerate(path.read_text("utf-8").splitlines()):
        if line.startswith("#"):
            m = re.search(r"corpus total (\d+)", line)
            if m:
                total = int(m.group(1))
            continue
        if not line.strip():
            continue
        w, c = line.split("\t")
        if cfg.fixture and len(words) >= 300 and w not in FIXTURE_ENGLISH:
            continue
        words[w] = int(c)
    ds.english_words = words
    ds.english_total = total or sum(words.values())


_LATIN_WORD = re.compile(r"[A-Za-z]+")


def gloss_english_words(ds: "Dataset", min_senses: int = 5) -> set[str]:
    """Words the definitions use as ordinary English — written in lowercase in at least `min_senses`
    senses — that are also a complete reading in this dataset's syllable inventory (`dim`, `tofu`
    would be `to fu`…): they count as English words for side selection although the common-word list
    lacks them (count 0, SCHEMA note 19). Capitalised occurrences (names, `Dian Hong`) do not count."""
    inv = set(ds.syllables)
    longest = max((len(x) for x in inv), default=0)

    def reads(w: str) -> bool:
        ok = [True] + [False] * len(w)
        for i in range(len(w)):
            if ok[i]:
                for j in range(i + 1, min(len(w), i + longest) + 1):
                    if w[i:j] in inv:
                        ok[j] = True
        return ok[len(w)]

    seen: dict[str, int] = defaultdict(int)
    for e in ds.entries:
        if e["unihan"]:
            continue
        for s in e["senses"]:
            for w in set(_LATIN_WORD.findall(s["t"])):
                if w.islower() and len(w) > 1:
                    seen[w] += 1
    return {w for w, n in seen.items() if n >= min_senses and reads(w)}


def natural_key(trad: str, simp: str, reading: str) -> str:
    return hashlib.sha256(f"{trad} {simp} {reading}".encode("utf-8")).digest()[:8].hex()


def assign_natural_keys(ds: Dataset) -> None:
    seen: dict[str, int] = {}
    dup = 0
    for e in ds.entries:
        k = natural_key(e["trad"], e["simp"], e["cased"])
        if k in seen:
            dup += 1
            n = 2
            while True:
                k2 = natural_key(e["trad"], e["simp"], f"{e['cased']}#{n}")
                if k2 not in seen:
                    k = k2
                    break
                n += 1
        seen[k] = e["id"]
        e["nkey"] = k
    ds.report["natural_key_collisions_disambiguated"] = dup


# ------------------------------------------------------------------------------------------
# Reference resolution (ENT-046, DATA-054)

class Resolver:
    def __init__(self, entries: list[dict]):
        self.entries = entries
        self.by_trad: dict[str, list[int]] = defaultdict(list)
        self.by_simp: dict[str, list[int]] = defaultdict(list)
        for e in entries:
            self.by_trad[fold(e["trad"])].append(e["id"])
            self.by_simp[fold(e["simp"])].append(e["id"])

    def candidates(self, tr: str, si: str) -> list[int]:
        ids = set(self.by_trad.get(tr, ())) | set(self.by_simp.get(si, ()))
        ids |= set(self.by_trad.get(si, ())) | set(self.by_simp.get(tr, ()))
        return sorted(ids)

    def resolve(self, ref: dict, exclude: int | None = None, reading_field: str = "pin") -> int | None:
        """Return the single best-tier entry id or None (ambiguous / not found)."""
        tr, si = ref["tr"], ref["si"]
        c = ref.get("c")
        cased = ref.get("_cased")
        best_tier = 99
        best: list[tuple[int, int, int]] = []
        for i in self.candidates(tr, si):
            if i == exclude:
                continue
            e = self.entries[i]
            et, es = fold(e["trad"]), fold(e["simp"])
            both = et == tr and es == si
            rmatch = c is not None and e.get("pin_canonical", e["canonical"]) == c
            tier = (1 if rmatch else 2) if both else (3 if rmatch else 4)
            exact = 0 if (rmatch and e.get("pin_cased", e["cased"]) == cased) else 1
            if tier < best_tier:
                best_tier, best = tier, [(exact, 1 if e["unihan"] else 0, i)]
            elif tier == best_tier:
                best.append((exact, 1 if e["unihan"] else 0, i))
        if not best:
            return None
        best.sort()
        top = [b for b in best if b[:2] == best[0][:2]]
        return top[0][2] if len(top) == 1 else None


def iter_refs(e: dict):
    for s in e["senses"]:
        for seg in s["seg"]:
            if seg["k"] == "ref":
                yield seg
    for n in e["notes"]:
        yield from n["refs"]
        for seg in n.get("_seg", ()):
            if seg["k"] == "ref":
                yield seg
    if e["redirect"]:
        for seg in e["redirect"]["seg"]:
            if seg["k"] == "ref":
                yield seg


def make_redirects(ds: Dataset) -> None:
    """PIPE-027: an entry without senses whose only variant note has one reference redirects."""
    n = 0
    for e in ds.entries:
        if e["senses"] or e["redirect"]:
            continue
        vn = [x for x in e["notes"] if x["kind"] in VARIANT_KINDS]
        if len(vn) != 1 or len(vn[0]["refs"]) != 1:
            continue
        note = vn[0]
        e["redirect"] = {"kind": VARIANT_KINDS[note["kind"]], "text": plain_text(note["_seg"], ds.script),
                         "seg": note["_seg"], "target": None, "resolved": False, "_ref": note["refs"][0]}
        e["notes"] = [x for x in e["notes"] if x is not note]    # DATA-061
        n += 1
    ds.report["redirects"] = n


def finish_redirects(ds: Dataset) -> None:
    res = unres = 0
    for e in ds.entries:
        r = e["redirect"]
        if not r:
            continue
        ref = next((s for s in r["seg"] if s["k"] == "ref"), None)
        tid = ref.get("id") if ref else None
        if tid is not None and tid != e["id"]:
            r["target"], r["resolved"] = tid, True
            res += 1
        else:
            r["target"], r["resolved"] = None, False
            unres += 1
    ds.report["redirects_resolved"] = res
    ds.report["redirects_unresolved"] = unres


def count_refs(ds: Dataset) -> None:
    total = unresolved = 0
    for e in ds.entries:
        refs = [seg for s in e["senses"] for seg in s["seg"] if seg["k"] == "ref"]
        refs += [r for n in e["notes"] for r in n["refs"]]
        if e["redirect"]:
            refs += [seg for seg in e["redirect"]["seg"] if seg["k"] == "ref"]
        for ref in refs:
            total += 1
            if ref.get("id") is None:
                unresolved += 1
    ds.report["references_total"] = total
    ds.report["references_unresolved"] = unresolved


# ------------------------------------------------------------------------------------------
# Alternative readings (DATA-192)

def build_alt_readings(ds: Dataset, parse) -> None:
    """`parse(raw)` -> (display, canonical, syllables, tones, full)."""
    stats = defaultdict(int)
    for e in ds.entries:
        main_syl = key_syllables(e["syllables"])
        main_tones = key_tones(e["syllables"], e["tones"])
        seen = {(reading_key(main_syl), main_tones)}
        for note in e["notes"]:
            if note["kind"] not in ("also_pr", "taiwan_pr"):
                continue
            for raw in note.get("_readings", ()):
                d, c, syl, tones, full = parse(raw)
                if not full:
                    stats[f"{note['kind']}_unparseable"] += 1
                    continue
                if len(syl) != len(main_syl):
                    stats[f"{note['kind']}_partial"] += 1
                    continue
                key = (reading_key(syl), tuple(tones))
                if key in seen:
                    stats[f"{note['kind']}_duplicate"] += 1
                    continue
                seen.add(key)
                alt = 2 if note["kind"] == "taiwan_pr" else 1
                e["alt_rows"].append((key[0], alt, len(e["alt"]), bytes(tones), tuple(syl)))
                e["alt"].append({"kind": note["kind"], "d": d, "c": c})
                stats[f"{note['kind']}_indexed"] += 1
    ds.report["alt_readings"] = dict(sorted(stats.items()))


# ------------------------------------------------------------------------------------------
# Merge groups (DATA-080…083)

def build_merge_groups(ds: Dataset) -> None:
    groups = []
    for script, form, alt in ((0, "simp", "trad"), (1, "trad", "simp")):
        buckets: dict[tuple, list[dict]] = defaultdict(list)
        for e in ds.entries:
            buckets[(e[form], e["display"])].append(e)
        for key in sorted(buckets):
            members = buckets[key]
            if len(members) < 2:
                continue
            members = sorted(members, key=lambda e: (-len(e["senses"]), e["id"]))
            variants = []
            for m in members:
                if m[alt] not in variants:
                    variants.append(m[alt])
            if len(variants) < 2:
                continue
            variants = [v for v in variants if v != key[0]]      # the display line never repeats the headword
            groups.append({"script": script, "members": [m["id"] for m in members], "variants": variants})
    for gid, g in enumerate(groups, 1):
        g["id"] = gid
        col = "merge_simp" if g["script"] == 0 else "merge_trad"
        for i in g["members"]:
            ds.entries[i][col] = gid
    ds.merge_groups = groups
    ds.report["merge_groups"] = {"simplified": sum(1 for g in groups if g["script"] == 0),
                                 "traditional": sum(1 for g in groups if g["script"] == 1)}


# ------------------------------------------------------------------------------------------
# Frequency (DATA-110…113), popular (DATA-090)

def assign_frequency(ds: Dataset, form_field: str, lm_logp: dict[str, float] | None = None,
                     lm_observed: set[str] | None = None) -> None:
    """Rank entries from ds.freq (count by surface form); fill unranked ones from LM unigrams."""
    freq = ds.freq
    ranked = sorted(freq.items(), key=lambda kv: (-kv[1], kv[0]))
    rank_of = {w: i + 1 for i, (w, _c) in enumerate(ranked)}
    n_table = 0
    pending: dict[str, float] = {}
    other = "trad" if form_field == "simp" else "simp"
    for e in ds.entries:
        if e["unihan"]:
            continue
        r = rank_of.get(e[form_field]) or rank_of.get(e[other])
        if r:
            e["freq_rank"] = r
            n_table += 1
        elif lm_observed is not None and e[form_field] in lm_observed:
            pending[e[form_field]] = lm_logp[e[form_field]]
    base = len(ranked)
    lm_rank = {w: base + i + 1 for i, (w, _lp) in enumerate(sorted(pending.items(), key=lambda kv: (-kv[1], kv[0])))}
    n_lm = 0
    for e in ds.entries:
        if e["freq_rank"] == UNRANKED and e[form_field] in lm_rank:
            e["freq_rank"] = lm_rank[e[form_field]]
            n_lm += 1
    ds.report["frequency"] = {"table_forms": len(freq), "total": ds.total_freq,
                              "entries_ranked_from_table": n_table, "entries_ranked_from_lm": n_lm,
                              "entries_unranked": sum(1 for e in ds.entries if e["freq_rank"] == UNRANKED)}
    # word_freq: every segmentable headword form up to MAX_WORD_LEN, both scripts. log_prob is the
    # language model's unigram of the entry's word (observed: the blended corpus estimate; unobserved:
    # the smoothed value below every observed one), so the segmenter and the decoder share one scale.
    total = max(ds.total_freq, 1)
    lm_floor = min((lm_logp[w] for w in lm_observed), default=math.log(0.5 / total)) if lm_logp and lm_observed else math.log(0.5 / total)
    wf: dict[str, tuple[int, float]] = {}
    for e in ds.entries:
        if not e["segmentable"]:
            continue
        cnt = freq.get(e[form_field]) or freq.get(e[other]) or 0
        if e["unihan"]:
            lp, observed = lm_floor - 8.0, False
        elif lm_logp is not None and e[form_field] in lm_logp:
            lp, observed = lm_logp[e[form_field]], e[form_field] in lm_observed
        elif cnt:
            lp, observed = math.log(cnt / total), True
        else:
            lp, observed = lm_floor - (2.0 if len(e[form_field]) > 1 else 5.0), False
        lp = round(lp, 3) if observed else float(round(lp))
        e["lp"] = lp
        for form in (e["simp"], e["trad"]):
            if len(form) > MAX_WORD_LEN:
                continue
            c = freq.get(form) or cnt
            old = wf.get(form)
            if old is None or (lp, c) > (old[1], old[0]):
                wf[form] = (c, lp)
    ds.word_freq = wf
    # popular: most frequent multi-character words
    best_entry: dict[str, dict] = {}
    for e in ds.entries:
        if e["unihan"] or len(e["simp"]) < 2 or not all(is_hanzi(ch) for ch in e[form_field]):
            continue
        w = e[form_field]
        if w not in freq:
            continue
        cur = best_entry.get(w)
        if cur is None or (-len(e["senses"]), e["id"]) < (-len(cur["senses"]), cur["id"]):
            best_entry[w] = e
    pop = sorted(best_entry.values(), key=lambda e: (-freq[e[form_field]], e["id"]))[:50]
    ds.popular = [e["id"] for e in pop]


# ------------------------------------------------------------------------------------------
# within (DATA-101)

def compute_within(ds: Dataset) -> dict[int, bytes]:
    """Reference of the inside-words rows the app computes on demand (DATA-101 / DATA-102; schema
    version 2 stores no `within` table): used by the golden queries."""
    by_form: dict[str, list[int]] = defaultdict(list)
    for e in ds.entries:
        by_form[e["simp"]].append(e["id"])
        if e["trad"] != e["simp"]:
            by_form[e["trad"]].append(e["id"])
    out: dict[int, bytes] = {}
    for e in ds.entries:
        n = len(e["simp"])
        if n < 2 or n > WITHIN_MAX or e["unihan"]:
            continue
        rows = set()
        for form in {e["simp"], e["trad"]}:
            if len(form) != n:
                continue
            for length in range(1, n):
                for start in range(0, n - length + 1):
                    for tid in by_form.get(form[start:start + length], ()):
                        if tid != e["id"]:
                            rows.add((start, length, tid))
        if not rows:
            continue
        E = ds.entries
        ordered = sorted(rows, key=lambda r: (-r[1], r[0], E[r[2]]["unihan"], E[r[2]]["freq_rank"], E[r[2]]["proper"],
                                              min(E[r[2]].get("rrank", 0), 15), r[2]))
        buf = bytearray()
        for s, l, t in ordered:
            buf += varint(s) + varint(l) + varint(t)
        out[e["id"]] = bytes(buf)
    return out


# ------------------------------------------------------------------------------------------
# Body / gloss

def _clean(obj):
    if isinstance(obj, dict):
        return {k: _clean(v) for k, v in obj.items() if not k.startswith("_")}
    if isinstance(obj, list):
        return [_clean(v) for v in obj]
    return obj


def note_text(note: dict, script: str) -> str:
    if note.get("_seg"):
        return plain_text(note["_seg"], script)
    lab = {"also_pr": "also pr.", "taiwan_pr": "Taiwan pr."}.get(note["kind"], note["kind"])
    return f"{lab} [{note['reading']}]" + (f", {note['extra']}" if note.get("extra") else "")


def entry_gloss(ds: Dataset, e: dict) -> str:
    if e["senses"]:
        return summarise(e["senses"])
    r = e["redirect"]
    if r:
        if r["resolved"]:
            t = ds.entries[r["target"]]
            tail = summarise(t["senses"], 200)
            return r["text"] + ("; " + tail if tail else "")
        return r["text"]
    return "; ".join(note_text(n, ds.script) for n in e["notes"])[:240]


def entry_body(e: dict) -> dict:
    """body JSON with the defaults of contract/SCHEMA.md omitted (tags, seg, tok, src, blocks, notes)."""
    senses = []
    for s in e["senses"]:
        o = {"t": s["t"]}
        if s["src"] != "cedict":
            o["src"] = s["src"]
        if s["tags"]:
            o["tags"] = s["tags"]
        if s["tok"] != en_tokens(s["t"]):
            o["tok"] = s["tok"]
        seg = _clean(s["seg"])
        if seg != [{"k": "t", "t": s["t"]}]:
            o["seg"] = seg
        if s.get("evr"):
            o["evr"] = True
        senses.append(o)
    body: dict = {"senses": senses}
    blocks = _clean(e["blocks"])
    if blocks != default_blocks(e):
        body["blocks"] = blocks
    if e["notes"]:
        body["notes"] = [{"kind": n["kind"], "label": n["label"], "refs": _clean(n["refs"]),
                          "reading": n.get("reading"), "extra": n.get("extra")} for n in e["notes"]]
    if e["redirect"]:
        body["redirect"] = _clean(e["redirect"])
    if e["alt"]:
        body["alt"] = e["alt"]
    return body


def default_blocks(e: dict) -> list:
    """The block list a reader assumes when `blocks` is absent: one `mandarin` block over all senses
    with a single reading group carrying the entry's own reading; nothing when there are no senses."""
    n = len(e["senses"])
    if not n:
        return []
    return [{"kind": "mandarin", "label": "block_mandarin", "first": 0, "count": n, "collapsed": False,
             "readings": [{"d": e["display"], "c": e["canonical"], "first": 0, "count": n}]}]


def expand_body(body: dict, display: str, canonical: str) -> dict:
    """Inverse of the omissions in entry_body (what the app does when reading)."""
    for s in body["senses"]:
        s.setdefault("src", "cedict")
        s.setdefault("tags", [])
        s.setdefault("tok", en_tokens(s["t"]))
        s.setdefault("seg", [{"k": "t", "t": s["t"]}])
    if "blocks" not in body:
        body["blocks"] = default_blocks({"senses": body["senses"], "display": display, "canonical": canonical})
    body.setdefault("notes", [])
    return body


def derived_display(ds: "Dataset", e: dict) -> str | None:
    """What the app shows for a NULL `reading_display` (schema version 2): Jyutping verbatim; Pinyin
    re-marked from the canonical reading when every token is a toned inventory syllable (or a comma /
    middle dot) — otherwise None, and the display string is stored."""
    if ds.id != "mandarin":
        return e["canonical"]
    from .roman import parse_pinyin
    p = parse_pinyin(e["canonical"])
    return p[0] if p[5] else None


def entry_ulp(ds: "Dataset", e: dict) -> int:
    """`entry.ulp`: the unigram log-probability of the entry's word × 100 (SCHEMA.md, schema version 2) —
    the value `word_freq.log_prob` held for its forms. Non-segmentable entries carry the lowest value."""
    lp = e.get("lp")
    return -6000 if lp is None or not e["segmentable"] else round(lp * 100)


def flags_of(e: dict) -> int:
    return (1 if e["proper"] else 0) | (2 if e["segmentable"] else 0) | (4 if e["unihan"] else 0) | (
        8 if e["canto"] else 0) | (min(e.get("rrank", 0), 15) << 4)


def unihan_reading_order(fields: dict[str, str] | None, mandarin: bool) -> list[str]:
    """Canonical readings of a character, most common first: Mandarin — kHanyuPinlu (corpus counts of
    each reading) then kMandarin; Cantonese — kCantonese (the customary reading comes first)."""
    if not fields:
        return []
    out: list[str] = []
    if mandarin:
        from .roman import marked_to_numbered
        raw = [re.sub(r"\(\d+\)", "", v) for v in fields.get("kHanyuPinlu", "").split()]
        raw += fields.get("kMandarin", "").split()
        for v in raw:
            n = marked_to_numbered(v, 1)
            if n and n not in out:
                out.append(n)
    else:
        for v in fields.get("kCantonese", "").split():
            if v not in out:
                out.append(v)
    return out


def assign_reading_ranks(ds: Dataset, unihan: dict[str, dict[str, str]]) -> None:
    """Primary-reading rank (`flags` bits 4-7, SCHEMA note 18): position of the entry among the entries
    sharing its headword — simplified form in the Mandarin dataset, traditional in the Cantonese one.
    Order: (a non-syllabic suffix reading last) → own HSK level (lowest first) → for a single character
    the Unihan reading order → common before proper noun → entries with senses → more senses → id.
    0 = the primary reading."""
    mand = ds.id == "mandarin"
    field = "simp" if mand else "trad"
    groups: dict[str, list[dict]] = defaultdict(list)
    for e in ds.entries:
        groups[e[field]].append(e)
    multi = decided_hsk = decided_unihan = 0
    for form, members in groups.items():
        if len(members) == 1:
            members[0]["rrank"] = 0
            continue
        multi += 1
        order: list[str] = []
        if len(form) == 1:
            order = unihan_reading_order(unihan.get(form), mand)
            for e in members:                          # the other-script form of the same character
                other = e["trad" if mand else "simp"]
                if len(other) == 1 and other != form:
                    for r in unihan_reading_order(unihan.get(other), mand):
                        if r not in order:
                            order.append(r)

        def key(e: dict):
            lv = min((x["l"] for x in e["hsk"]), default=99)
            u = order.index(e["canonical"]) if e["canonical"] in order else len(order)
            # a non-syllabic reading (儿 `r`, the erhua suffix) never stands for the bare character
            bound = len(form) == 1 and e["canonical"] == "r5"
            return (e["unihan"], bound, lv, u, e["proper"], not e["senses"], -len(e["senses"]), e["id"])

        ranked = sorted(members, key=key)
        for i, e in enumerate(ranked):
            e["rrank"] = i
        k0, k1 = key(ranked[0]), key(ranked[1])
        if k0[2] != k1[2]:
            decided_hsk += 1
        elif k0[3] != k1[3]:
            decided_unihan += 1
    ds.report["reading_rank"] = {"headwords_with_several_entries": multi, "primary_decided_by_hsk_level": decided_hsk,
                                 "primary_decided_by_unihan_reading_order": decided_unihan}


# ------------------------------------------------------------------------------------------
# Index builders

def headword_rows(ds: Dataset) -> list[tuple[str, int, int]]:
    rows = set()
    uni_to_rad: dict[str, list[str]] = defaultdict(list)
    for rad, unis in ds.radical_forms.items():
        for uni in unis:
            uni_to_rad[uni].append(rad)
    for e in ds.entries:
        for form in {e["simp"], e["trad"]}:
            rows.add((form, e["id"], 0))
            f = fold(form)
            if f != form:
                rows.add((f, e["id"], 1))
            k = matching_key(form)
            if k and k != form and k != f:
                rows.add((k, e["id"], 2))
            for rad in uni_to_rad.get(form, ()):
                rows.add((rad, e["id"], 3))
    return sorted(rows)


def substr_postings(ds: Dataset) -> dict[str, list[int]]:
    post: dict[str, set[int]] = defaultdict(set)
    for e in ds.entries:
        for form in {matching_key(e["simp"]), matching_key(e["trad"])}:
            n = len(form)
            for start in range(1, n):
                for length in range(1, min(SUBSTR_MAX, n - start) + 1):
                    post[form[start:start + length]].add(e["id"])
    return {k: sorted(v) for k, v in post.items() if k.strip()}


def char_postings(ds: Dataset) -> dict[str, list[int]]:
    post: dict[str, set[int]] = defaultdict(set)
    for e in ds.entries:
        for ch in set(e["simp"] + e["trad"]):
            if is_hanzi(ch):
                post[ch].add(e["id"])
    return {ch: sorted(ids, key=lambda i: (ds.entries[i]["freq_rank"], i)) for ch, ids in post.items()}


def en_postings(ds: Dataset) -> dict[str, bytes]:
    """token -> encoded postings sorted by (entry_id, sense)."""
    post: dict[str, set[tuple[int, int, int]]] = defaultdict(set)
    if ds.en_primary is not None:
        for tok, rows in ds.en_primary.items():
            post[tok] |= rows
    else:
        for e in ds.entries:
            fl = 2 if e["unihan"] else 0
            for i, s in enumerate(e["senses"]):
                for tok in set(s["tok"]):
                    post[tok].add((e["id"], i, fl))
    out = {}
    for tok, rows in post.items():
        buf = bytearray()
        prev = 0
        for eid, sense, fl in sorted(rows):
            buf += varint(eid - prev) + varint((sense << 2) | fl)
            prev = eid
        out[tok] = bytes(buf)
    return out


# ------------------------------------------------------------------------------------------
# Writer

MAIN_DDL = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
CREATE TABLE entry(
  id INTEGER PRIMARY KEY, trad TEXT NOT NULL, simp TEXT NOT NULL,
  reading_display TEXT, reading_canonical TEXT NOT NULL, reading_source INTEGER NOT NULL,
  syllables TEXT, tones BLOB, flags INTEGER NOT NULL, freq_rank INTEGER NOT NULL,
  char_count INTEGER NOT NULL, natural_key BLOB NOT NULL, merge_simp INTEGER, merge_trad INTEGER,
  hsk TEXT NOT NULL, hsk_via TEXT NOT NULL, audio_key TEXT, ulp INTEGER NOT NULL, body BLOB NOT NULL);
CREATE UNIQUE INDEX entry_natural ON entry(natural_key);
CREATE TABLE body_dict(id INTEGER PRIMARY KEY, dict BLOB NOT NULL);
CREATE TABLE headword(key TEXT, entry_id INTEGER, kind INTEGER, PRIMARY KEY(key, entry_id, kind)) WITHOUT ROWID;
CREATE TABLE reading(key TEXT, entry_id INTEGER, alt INTEGER, alt_note INTEGER, tones BLOB,
  PRIMARY KEY(key, entry_id, alt, alt_note)) WITHOUT ROWID;
CREATE TABLE substr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
CREATE TABLE char_idx(ch TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
CREATE TABLE en_token(token TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
CREATE TABLE merge_group(id INTEGER PRIMARY KEY, script INTEGER, members BLOB, variants TEXT);
CREATE TABLE hsk_row(row_id INTEGER PRIMARY KEY, ord INTEGER, level INTEGER, simp TEXT, trad TEXT,
  homograph INTEGER, pos TEXT, extra_levels TEXT, entry_id INTEGER NOT NULL, also_entry_ids TEXT, sort_key TEXT);
CREATE INDEX hsk_row_level ON hsk_row(level, ord);
CREATE TABLE hsk_char(ch TEXT PRIMARY KEY, ord INTEGER, level INTEGER, writing INTEGER,
  writing_band TEXT, entry_id INTEGER, sort_key TEXT) WITHOUT ROWID;
CREATE INDEX hsk_char_level ON hsk_char(level, ord);
CREATE TABLE hsk_pos_label(token TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID;
CREATE TABLE label(key TEXT PRIMARY KEY, en TEXT, zh_hans TEXT, zh_hant TEXT) WITHOUT ROWID;
CREATE TABLE syllable(value TEXT PRIMARY KEY, idx INTEGER) WITHOUT ROWID;
CREATE TABLE syllable_alias(src TEXT PRIMARY KEY, dst TEXT) WITHOUT ROWID;
CREATE TABLE syllable_abbrev(abbrev TEXT, syllable TEXT, PRIMARY KEY(abbrev, syllable)) WITHOUT ROWID;
CREATE TABLE popular(rank INTEGER PRIMARY KEY, entry_id INTEGER);
CREATE TABLE english_word(word TEXT PRIMARY KEY, freq INTEGER) WITHOUT ROWID;
"""

LM_DDL = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID;
CREATE TABLE lm_entry(entry_id INTEGER PRIMARY KEY, lm_word INTEGER, lm_unigram INTEGER);
CREATE TABLE lm_codebook(kind INTEGER, code INTEGER, value REAL, PRIMARY KEY(kind, code)) WITHOUT ROWID;
CREATE TABLE abbr_idx(key TEXT PRIMARY KEY, postings BLOB) WITHOUT ROWID;
CREATE TABLE bigram(left INTEGER PRIMARY KEY, block BLOB);
"""

HASH_EXCLUDED_META = {"content_hash", "built_at"}


def open_new(path: Path, ddl: str) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    for suffix in ("", "-journal", "-wal", "-shm"):
        p = Path(str(path) + suffix)
        if p.exists():
            p.unlink()
    con = sqlite3.connect(path, isolation_level=None)
    con.execute("PRAGMA page_size=4096")
    con.execute("PRAGMA journal_mode=DELETE")
    con.execute("PRAGMA synchronous=OFF")
    con.execute("BEGIN")
    con.executescript(ddl) if False else [con.execute(s) for s in ddl.split(";") if s.strip()]
    return con


def finish(con: sqlite3.Connection) -> None:
    con.execute("COMMIT")
    con.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
    con.execute("ANALYZE")
    con.execute("VACUUM")
    con.close()


def content_hash(con: sqlite3.Connection) -> str:
    """sha256 over every table (name order), rows in primary-key order, typed length-prefixed values.
    meta rows `content_hash` and `built_at` are excluded (DATA-006)."""
    h = hashlib.sha256()
    tables = [r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    for t in tables:
        h.update(b"T" + t.encode() + b"\0")
        ncol = len(con.execute(f"PRAGMA table_info({t})").fetchall())
        order = ",".join(str(i) for i in range(1, ncol + 1))
        for row in con.execute(f"SELECT * FROM {t} ORDER BY {order}"):
            if t == "meta" and row[0] in HASH_EXCLUDED_META:
                continue
            for v in row:
                if v is None:
                    h.update(b"N")
                elif isinstance(v, int):
                    b = str(v).encode()
                    h.update(b"I" + struct.pack(">I", len(b)) + b)
                elif isinstance(v, float):
                    h.update(b"F" + struct.pack(">d", v))
                elif isinstance(v, str):
                    b = v.encode("utf-8")
                    h.update(b"S" + struct.pack(">I", len(b)) + b)
                else:
                    h.update(b"B" + struct.pack(">I", len(v)) + bytes(v))
            h.update(b"\n")
    return h.hexdigest()


def write_main(ds: Dataset, path: Path) -> dict:
    con = open_new(path, MAIN_DDL)
    counts: dict[str, int] = {}
    ents = ds.entries
    zdict = ds.body_dict or None
    if zdict:
        con.execute("INSERT INTO body_dict VALUES(1, ?)", (zdict,))

    def row(e: dict) -> tuple:
        syl, tones = " ".join(e["syllables"]), bytes(e["tones"])
        derived = derive_reading_tokens(e["canonical"])
        if (" ".join(derived[0]), bytes(derived[1])) == (syl, tones):
            syl = tones = None                                  # the app derives them (schema version 2)
        display = None if derived_display(ds, e) == e["display"] else e["display"]
        return (e["id"], e["trad"], e["simp"], display, e["canonical"], e["rsrc"], syl, tones, flags_of(e),
                e["freq_rank"], len(e["simp"]), bytes.fromhex(e["nkey"]), e["merge_simp"], e["merge_trad"],
                cjson(e["hsk"]) if e["hsk"] else "", cjson(e["hsk_via"]) if e["hsk_via"] else "",
                e["audio_key"], entry_ulp(ds, e), zjson(entry_body(e), zdict))

    con.executemany("INSERT INTO entry VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (row(e) for e in ents))
    counts["entry"] = len(ents)

    hw = headword_rows(ds)
    con.executemany("INSERT INTO headword VALUES(?,?,?)", hw)
    counts["headword"] = len(hw)
    counts["headword_keys"] = len({r[0] for r in hw})

    rd = set()
    for e in ents:
        key = reading_key(e["syllables"])
        if key and e["rsrc"] != RSRC["none"]:
            rd.add((key, e["id"], 0, 0, bytes(key_tones(e["syllables"], e["tones"]))))
        for key, alt, ordinal, tones, _syl in e["alt_rows"]:
            rd.add((key, e["id"], alt, ordinal, tones))
    rd = sorted(rd)
    con.executemany("INSERT INTO reading VALUES(?,?,?,?,?)", rd)
    counts["reading"] = len(rd)

    sub = substr_postings(ds)
    con.executemany("INSERT INTO substr_idx VALUES(?,?)", ((k, delta_list(v)) for k, v in sorted(sub.items())))
    counts["substr_keys"] = len(sub)
    counts["substr_postings"] = sum(len(v) for v in sub.values())

    ch = char_postings(ds)
    con.executemany("INSERT INTO char_idx VALUES(?,?)", ((k, plain_list(v)) for k, v in sorted(ch.items())))
    counts["char_keys"] = len(ch)
    counts["char_postings"] = sum(len(v) for v in ch.values())

    en = en_postings(ds)
    con.executemany("INSERT INTO en_token VALUES(?,?)", sorted(en.items()))
    counts["en_tokens"] = len(en)

    con.executemany("INSERT INTO merge_group VALUES(?,?,?,?)",
                    ((g["id"], g["script"], plain_list(g["members"]), cjson(g["variants"])) for g in ds.merge_groups))
    counts["merge_group"] = len(ds.merge_groups)


    con.executemany(
        "INSERT INTO hsk_row VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        ((r["row_id"], r["ord"], r["level"], r["simp"], r["trad"], r["homograph"], cjson(r["pos"]),
          cjson(r["extra_levels"]), r["entry_id"], cjson(r["also_entry_ids"]), r["sort_key"])
         for r in sorted(ds.hsk_rows, key=lambda r: r["row_id"])))
    counts["hsk_row"] = len(ds.hsk_rows)
    con.executemany("INSERT INTO hsk_char VALUES(?,?,?,?,?,?,?)",
                    ((c["ch"], c["ord"], c["level"], c["writing"], c["writing_band"], c["entry_id"], c["sort_key"])
                     for c in sorted(ds.hsk_chars, key=lambda c: c["ch"])))
    counts["hsk_char"] = len(ds.hsk_chars)
    lab = labels()
    con.executemany("INSERT INTO hsk_pos_label VALUES(?,?,?,?)",
                    sorted((p["token"], p["en"], p["zh_hans"], p["zh_hant"]) for p in lab.hsk_pos))
    con.executemany("INSERT INTO label VALUES(?,?,?,?)",
                    sorted((r["key"], r["en"], r["zh_hans"], r["zh_hant"]) for r in lab.rows))
    con.executemany("INSERT INTO syllable VALUES(?,?)", ((s, i) for i, s in enumerate(ds.syllables)))
    con.executemany("INSERT INTO syllable_alias VALUES(?,?)", sorted(ds.aliases.items()))
    con.executemany("INSERT INTO syllable_abbrev VALUES(?,?)", sorted(set(ds.abbrevs)))
    con.executemany("INSERT INTO popular VALUES(?,?)", ((i + 1, eid) for i, eid in enumerate(ds.popular)))
    counts["popular"] = len(ds.popular)
    words = dict(ds.english_words)
    gloss = gloss_english_words(ds)
    for w in gloss:
        words.setdefault(w, 0)
    con.executemany("INSERT INTO english_word VALUES(?,?)", sorted(words.items()))
    counts["english_word"] = len(words)
    counts["english_word_from_glosses"] = sum(1 for w in gloss if w not in ds.english_words)

    meta = dict(ds.meta)
    meta["entry_count"] = str(len(ents))
    meta["max_headword_len"] = str(max((len(e["simp"]) for e in ents), default=0))
    con.executemany("INSERT INTO meta VALUES(?,?)", sorted(meta.items()))
    digest = content_hash(con)
    con.execute("INSERT INTO meta VALUES('content_hash',?)", (digest,))
    con.execute("INSERT INTO meta VALUES('built_at',?)", (ds.meta_built_at,))
    finish(con)
    counts["content_hash"] = digest
    counts["file_size"] = os.path.getsize(path)
    return counts


def audio_word_key(e: dict) -> str | None:
    """MED-005 canonical word key, or None when a syllable carries no tone digit."""
    toks = e["canonical"].split()
    if not toks or any(not re.fullmatch(r"[a-zü]+[1-6]", t) for t in toks):
        return None
    return "_".join(t.replace("ü", "v") for t in toks)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()
