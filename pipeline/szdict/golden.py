"""Golden-query suite (PIPE-140…142): data-level expectations evaluated on the built datasets and
shared with the app in contract/golden-queries.json.

Each case names a lookup the dataset must answer and the entries (by headword, reading and natural
key) it must yield, in the canonical order of the lookup. Full search ranking (match classes, SRCH-070)
is the app's; the cases pin what the data gives it.
"""
from __future__ import annotations

import json
from collections import defaultdict

from . import lm as lmmod
from .core import RSRC, UNRANKED, Dataset, default_blocks
from .norm import en_tokens, matching_key
from .roman import key_syllables, key_tones, reading_key

CASES = [
    # --- Mandarin
    {"id": "m-reading-toned", "mode": "mandarin", "type": "reading_exact", "query": "ni3hao3", "syllables": ["ni", "hao"], "tones": [3, 3]},
    {"id": "m-reading-toneless", "mode": "mandarin", "type": "reading_exact", "query": "nihao", "syllables": ["ni", "hao"]},
    {"id": "m-reading-initialism", "mode": "mandarin", "type": "reading_exact", "query": "t xu4", "syllables": ["t", "xu"], "tones": [0, 4]},
    {"id": "m-reading-alt-taiwan", "mode": "mandarin", "type": "reading_exact", "query": "le4 se4", "syllables": ["le", "se"], "tones": [4, 4]},
    {"id": "m-reading-fa1", "mode": "mandarin", "type": "reading_exact", "query": "fa1", "syllables": ["fa"], "tones": [1]},
    {"id": "m-abbr-nh", "mode": "mandarin", "type": "abbrev", "query": "nh", "top": 3},
    {"id": "m-abbr-zg", "mode": "mandarin", "type": "abbrev", "query": "zg", "top": 3},
    {"id": "m-abbr-zhg", "mode": "mandarin", "type": "abbrev", "query": "zhg", "key": "zg", "initials": ["zh", "g"], "top": 3},
    {"id": "m-combo-wxhn", "mode": "mandarin", "type": "combination", "query": "wxhn"},
    {"id": "m-combo-wszgr", "mode": "mandarin", "type": "combination", "query": "wszgr"},
    {"id": "m-english-phrase", "mode": "mandarin", "type": "english", "query": "graduate student", "top": 5},
    {"id": "m-english-love", "mode": "mandarin", "type": "english", "query": "love", "top": 5},
    {"id": "m-english-unihan", "mode": "mandarin", "type": "english", "query": "hillock", "top": 5},
    {"id": "m-hanzi-le", "mode": "mandarin", "type": "headword_exact", "query": "了"},
    {"id": "m-hanzi-hou", "mode": "mandarin", "type": "headword_exact", "query": "后"},
    {"id": "m-hanzi-case", "mode": "mandarin", "type": "headword_exact", "query": "aa制"},
    {"id": "m-hanzi-case-k", "mode": "mandarin", "type": "headword_exact", "query": "k金"},
    {"id": "m-hanzi-fold", "mode": "mandarin", "type": "headword_exact", "query": "Ｔ恤"},
    {"id": "m-hanzi-middle-dot", "mode": "mandarin", "type": "headword_exact", "query": "马丁・路德•金"},
    {"id": "m-hanzi-unihan", "mode": "mandarin", "type": "headword_exact", "query": "㐀"},
    {"id": "m-hanzi-radical-form", "mode": "mandarin", "type": "headword_exact", "query": "⻊", "raw": True},
    {"id": "m-segment", "mode": "mandarin", "type": "segmentation", "query": "我们研究生命科学"},
    {"id": "m-merge-hou", "mode": "mandarin", "type": "merge_group", "query": "后", "script": 0},
    {"id": "m-merge-zan", "mode": "mandarin", "type": "merge_group", "query": "赞", "script": 0},
    {"id": "m-redirect-erhua", "mode": "mandarin", "type": "redirect", "query": "一点儿"},
    {"id": "m-redirect-latin", "mode": "mandarin", "type": "redirect", "query": "B格"},
    {"id": "m-redirect-unresolved", "mode": "mandarin", "type": "redirect", "query": "妖蛾子"},
    {"id": "m-hsk-dian", "mode": "mandarin", "type": "hsk_entry", "query": "点"},
    {"id": "m-hsk-ban", "mode": "mandarin", "type": "hsk_row", "query": "半"},
    {"id": "m-hsk-hou", "mode": "mandarin", "type": "hsk_row", "query": "后"},
    {"id": "m-hsk-patch", "mode": "mandarin", "type": "hsk_row", "query": "精彩纷呈"},
    {"id": "m-hsk-patch-erhua", "mode": "mandarin", "type": "hsk_row", "query": "口哨儿"},
    {"id": "m-hsk-via-variant", "mode": "mandarin", "type": "hsk_entry", "query": "骨董"},
    {"id": "m-hsk-chars-l1", "mode": "mandarin", "type": "hsk_chars", "level": 1, "top": 10},
    {"id": "m-within", "mode": "mandarin", "type": "within", "query": "研究生"},
    {"id": "m-popular", "mode": "mandarin", "type": "popular", "top": 10},
    # --- Cantonese
    {"id": "c-hsk-nihao", "mode": "cantonese", "type": "hsk_row", "query": "你好"},
    {"id": "c-hanzi-bank", "mode": "cantonese", "type": "headword_exact", "query": "銀行", "blocks": True},
    {"id": "c-hanzi-hang", "mode": "cantonese", "type": "headword_exact", "query": "行", "blocks": True},
    {"id": "c-reading-ngodei", "mode": "cantonese", "type": "reading_exact", "query": "ngo5 dei6", "syllables": ["ngo", "dei"], "tones": [5, 6]},
    {"id": "c-abbr-nd", "mode": "cantonese", "type": "abbrev", "query": "nd", "top": 3},
    {"id": "c-abbr-ngd", "mode": "cantonese", "type": "abbrev", "query": "ngd", "key": "nd", "initials": ["ng", "d"], "top": 3},
    {"id": "c-abbr-gwdw", "mode": "cantonese", "type": "abbrev", "query": "gwdw", "key": "gdw", "initials": ["gw", "d", "w"], "top": 3},
    {"id": "c-english-walk", "mode": "cantonese", "type": "english", "query": "walk", "top": 12, "flags": True},
    {"id": "c-none-entry", "mode": "cantonese", "type": "headword_exact", "query": "上升", "blocks": True},
    {"id": "c-none-entry-no-reading", "mode": "cantonese", "type": "reading_rows", "query": "上升"},
    {"id": "c-segment-pronoun-ge", "mode": "cantonese", "type": "segmentation", "query": "佢嘅朋友係我哋嘅老師"},
    {"id": "c-hsk-chars-l1", "mode": "cantonese", "type": "hsk_chars", "level": 1, "top": 10},
    {"id": "c-popular", "mode": "cantonese", "type": "popular", "top": 10},
]


def desc(e: dict) -> dict:
    return {"simp": e["simp"], "trad": e["trad"], "reading": e["canonical"], "key": e["nkey"]}


class Index:
    def __init__(self, ds: Dataset, lm):
        self.ds, self.lm = ds, lm
        self.head: dict[str, set[int]] = defaultdict(set)
        from .core import headword_rows
        for key, eid, _kind in headword_rows(ds):
            self.head[key].add(eid)
        self.read: dict[str, list[tuple]] = defaultdict(list)
        for e in ds.entries:
            if e["rsrc"] != RSRC["none"]:
                k = reading_key(e["syllables"])
                if k:
                    self.read[k].append((e["id"], 0, key_tones(e["syllables"], e["tones"])))
            for k, alt, _ord, tones, _syl in e["alt_rows"]:
                self.read[k].append((e["id"], alt, tuple(tones)))
        self.syl = ds.syllables

    def order(self, ids, alt=None):
        E = self.ds.entries
        return sorted(ids, key=lambda i: (E[i]["unihan"], (alt or {}).get(i, 0) > 0, E[i]["freq_rank"], len(E[i]["simp"]),
                                          E[i]["proper"], i))


def evaluate(case: dict, ix: Index):
    ds, lm = ix.ds, ix.lm
    E = ds.entries
    t = case["type"]
    q = case.get("query", "")
    top = case.get("top")
    if t == "headword_exact":
        ids = ix.head.get(q if case.get("raw") else matching_key(q), set()) | ix.head.get(q, set())
        out = []
        for i in ix.order(ids):
            d = desc(E[i])
            if case.get("blocks"):
                blocks = E[i]["blocks"]
                d["blocks"] = [{"kind": b["kind"], "count": b["count"], "collapsed": b["collapsed"],
                                "readings": [g["c"] for g in b.get("readings", [])]} for b in blocks]
                d["reading_source"] = E[i]["rsrc"]
            out.append(d)
        return out
    if t == "reading_exact":
        rows = ix.read.get(" ".join(case["syllables"]), [])
        want = case.get("tones")
        alt = {}
        for eid, a, tones in rows:
            if want and any(w and w != x for w, x in zip(want, tones)):
                continue
            alt[eid] = min(a, alt.get(eid, 9))
        return [dict(desc(E[i]), alt=alt[i]) for i in ix.order(alt, alt)][:top or 10]
    if t == "reading_rows":
        ids = ix.head.get(q, set())
        return {"entries": len(ids), "reading_rows": sum(1 for i in ids if E[i]["rsrc"] != RSRC["none"])}
    if t == "abbrev":
        key = case.get("key", q)
        out = []
        seen = set()
        for _neg, eid, _wid, _code, alt, syl in lm.abbr.get(key, ()):
            if "initials" in case and not all(ix.syl[s].startswith(p) for s, p in zip(syl, case["initials"])):
                continue
            if eid in seen:
                continue
            seen.add(eid)
            out.append(dict(desc(E[eid]), alt=alt))
            if len(out) >= (top or 5):
                break
        return out
    if t == "combination":
        best = lmmod.decode_abbr(ds, lm, q, nbest=1)
        return best[0][0] if best else []
    if t == "english":
        toks = en_tokens(q)
        hits: dict[int, int] = {}
        if ds.en_primary is not None:
            cand = None
            for tk in toks:
                rows = {(eid, i): fl for eid, i, fl in ds.en_primary.get(tk, ())}
                cand = rows if cand is None else {k: v for k, v in cand.items() if k in rows}
            for (eid, _i), fl in sorted((cand or {}).items()):
                hits[eid] = 1 if fl == 1 or hits.get(eid) == 1 else fl
        else:
            for e in E:
                for s in e["senses"]:
                    if all(tk in s["tok"] for tk in toks):
                        hits[e["id"]] = 2 if e["unihan"] else 0
                        break
        ids = sorted(hits, key=lambda i: (hits[i] != 1 if ds.id == "cantonese" else False, E[i]["unihan"], E[i]["freq_rank"],
                                          len(E[i]["simp"]), E[i]["proper"], i))
        return [dict(desc(E[i]), **({"flags": hits[i]} if case.get("flags") else {})) for i in ids][:top or 10]
    if t == "segmentation":
        wf = ds.word_freq
        n = len(q)
        best = [0.0] + [-1e18] * n
        back = [0] * (n + 1)
        maxlen = 12
        for i in range(n):
            for j in range(i + 1, min(n, i + maxlen) + 1):
                hit = wf.get(q[i:j])
                lp = hit[1] if hit else (-20.0 if j == i + 1 else None)
                if lp is not None and best[i] + lp > best[j]:
                    best[j], back[j] = best[i] + lp, i
        dag = []
        j = n
        while j > 0:
            dag.append(q[back[j]:j])
            j = back[j]
        greedy = []
        i = 0
        while i < n:
            for j in range(min(n, i + maxlen), i, -1):
                if q[i:j] in wf or j == i + 1:
                    greedy.append(q[i:j])
                    i = j
                    break
        return {"dag": dag[::-1], "greedy": greedy}
    if t == "merge_group":
        form = "simp" if case["script"] == 0 else "trad"
        out = []
        for g in ds.merge_groups:
            if g["script"] == case["script"] and E[g["members"][0]][form] == q:
                out.append({"reading": E[g["members"][0]]["canonical"], "variants": g["variants"],
                            "members": [desc(E[i]) for i in g["members"]]})
        return out
    if t == "redirect":
        out = []
        for i in ix.order(ix.head.get(q, set())):
            r = E[i]["redirect"]
            if r:
                out.append({"entry": desc(E[i]), "kind": r["kind"], "resolved": r["resolved"],
                            "target": desc(E[r["target"]]) if r["resolved"] else None})
        return out
    if t == "hsk_entry":
        return [dict(desc(E[i]), hsk=E[i]["hsk"], hsk_via=E[i]["hsk_via"]) for i in ix.order(ix.head.get(q, set()))
                if E[i]["hsk"] or E[i]["hsk_via"]]
    if t == "hsk_row":
        return [{"row_id": r["row_id"], "level": r["level"], "homograph": r["homograph"], "pos": r["pos"],
                 "extra_levels": r["extra_levels"], "entry": desc(E[r["entry_id"]]),
                 "also": [desc(E[i]) for i in r["also_entry_ids"]], "sort_key": r["sort_key"]}
                for r in sorted(ds.hsk_rows, key=lambda r: r["ord"]) if r["simp"] == q]
    if t == "hsk_chars":
        rows = sorted((c for c in ds.hsk_chars if c["level"] == case["level"]), key=lambda c: c["ord"])[:top]
        return [{"ch": c["ch"], "entry": desc(E[c["entry_id"]])} for c in rows]
    if t == "within":
        from .core import compute_within
        from .enc import read_varints
        out = []
        w = compute_within(ds)
        for i in ix.order(ix.head.get(q, set())):
            vals = read_varints(w.get(i, b""))
            out.append([[vals[k], vals[k + 1], E[vals[k + 2]]["simp"], E[vals[k + 2]]["canonical"]] for k in range(0, len(vals), 3)])
        return out
    if t == "popular":
        return [E[i]["simp"] if ds.id == "mandarin" else E[i]["trad"] for i in ds.popular[:top]]
    raise ValueError(t)


def run(datasets: dict, cfg, update: bool) -> tuple[bool, dict]:
    """Evaluate every case; compare with (or rewrite) contract/golden-queries.json."""
    path = cfg.contract / "golden-queries.json"
    slot = "fixture" if cfg.fixture else "full"
    stored = json.loads(path.read_text("utf-8")) if path.exists() else {"cases": []}
    by_id = {c["id"]: c for c in stored.get("cases", [])}
    ix = {m: Index(ds, lm) for m, (ds, lm) in datasets.items()}
    diffs = {}
    cases = []
    for case in CASES:
        prev = by_id.get(case["id"], {})
        rec = dict(case)
        for s in ("fixture", "full"):
            if s in prev:
                rec[s] = prev[s]
        if case["mode"] in ix:
            got = json.loads(json.dumps(evaluate(case, ix[case["mode"]]), ensure_ascii=False))
            if update or slot not in rec:
                rec[slot] = got
            elif rec[slot] != got:
                diffs[case["id"]] = {"expected": rec[slot], "got": got}
        cases.append(rec)
    if update or not path.exists() or [c["id"] for c in stored.get("cases", [])] != [c["id"] for c in cases] \
            or any(slot not in by_id.get(c["id"], {}) and slot in c for c in cases):
        doc = {"_comment": "Golden queries (PIPE-140, PIPE-141). Generated by `pipeline/build.py --update-golden`; "
                           "`fixture` = expected result on contract/fixture/, `full` = on the full datasets. "
                           "See pipeline/szdict/golden.py for the meaning of each `type`.",
               "types": TYPES, "cases": cases}
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", "utf-8")
    return (not diffs), diffs


TYPES = {
    "headword_exact": "entries whose headword key (any kind) equals the SRCH-010 key of `query` (or the raw query when `raw`), "
                      "ordered by (Unihan-only last, freq_rank, char_count, proper noun last, id)",
    "reading_exact": "entries with a reading row for the toneless `syllables` (space-joined key) whose tones match `tones` "
                     "(0 = any): dictionary entries before Unihan-only ones (SRCH-079), then main-reading matches before alternative-reading "
                     "matches (`alt` 1 also_pr, 2 taiwan_pr), then freq_rank, char_count, proper noun last, id",
    "reading_rows": "number of entries of the headword and how many of them have a reading row (0 for reading_source none)",
    "abbrev": "first postings of abbr_idx[`key` or `query`] in stored order, filtered to syllables starting with `initials`, one per entry",
    "combination": "best word sequence of the lattice decoder for a pure first-letter query (bigram model, DATA-153 scoring)",
    "english": "entries with one sense containing every SRCH-013 token of `query`; Cantonese: CC-Canto postings (flags 1) first, "
               "Mandarin-block senses only on the primary Cantonese entry",
    "segmentation": "`dag`: maximum-probability segmentation with word_freq.log_prob (unknown single character = -20, TXT-012); `greedy`: forward longest match",
    "merge_group": "merge groups whose primary has the headword in the given script (0 simplified, 1 traditional)",
    "redirect": "redirect of each entry with that headword: kind, resolved flag and target",
    "hsk_entry": "entry.hsk / entry.hsk_via of the entries with that headword",
    "hsk_row": "HSK rows of the word in storage order with primary and also-linked entries",
    "hsk_chars": "first HSK recognition characters of the level in storage order with their primary entry",
    "within": "precomputed within rows of each entry with that headword: [start, length, simplified, reading]",
    "popular": "first entries of the popular list (headword in the dataset's main script)",
}
