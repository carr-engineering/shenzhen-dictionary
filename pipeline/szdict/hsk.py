"""HSK join (PIPE-040…046, PIPE-082, DATA-120…126, DATA-193, DATA-194)."""
from __future__ import annotations

import csv
import re
from collections import defaultdict, deque
from pathlib import Path

from .roman import parse_pinyin, respace_numbered, sort_reading

LEVEL_COUNTS = {1: 300, 2: 200, 3: 500, 4: 1000, 5: 1600, 6: 1800, 7: 5600}
CHAR_COUNTS = {1: 246, 2: 125, 3: 284, 4: 441, 5: 431, 6: 413, 7: 1148}
WRITING_COUNTS = {"1-2": 100, "3": 150, "4": 150, "5": 150, "6": 150, "7-9": 500}


def _level(s: str) -> int:
    return 7 if s.strip() == "7-9" else int(s)


def _pos(s: str) -> list[str]:
    out = []
    for t in s.split("、"):
        t = t.strip()
        if t and t not in out:
            out.append(t)
    return out


def read_rows(path: Path) -> tuple[list[dict], dict]:
    """One syllabus word per row: main level + extra levels (PIPE-040, PIPE-043)."""
    groups: dict[str, list[dict]] = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            groups.setdefault(r["word_index"], []).append(r)
    rows = []
    seen: dict[tuple, dict] = {}
    collisions = 0
    for idx, grp in groups.items():
        first = grp[0]
        m = re.fullmatch(r"(.*?)(\d*)", first["word"])
        simp, homograph = m.group(1), int(m.group(2) or 0)
        levels: dict[int, list[str]] = {}
        for r in grp:
            lv = _level(r["level"])
            cur = levels.setdefault(lv, [])
            for t in _pos(r["part_of_speech"]):
                if t not in cur:
                    cur.append(t)
        main = _level(first["level"])
        key = (main, simp, first["pinyin"], homograph)
        if key in seen:                       # DATA-123 de-duplication per (level, syllabus key)
            collisions += 1
            tgt = seen[key]
            for t in levels[main]:
                if t not in tgt["pos"]:
                    tgt["pos"].append(t)
            continue
        row = {"row_id": int(idx), "level": main, "simp": simp, "homograph": homograph,
               "pos": levels[main],
               "extra_levels": [{"l": lv, "pos": p} for lv, p in sorted(levels.items()) if lv != main],
               "syllabus_pinyin": first["pinyin"],
               "cedict_pinyin": first["pinyin_cc-cedict"], "cedict_trad": first["traditional_cc-cedict"]}
        seen[key] = row
        rows.append(row)
    info = {"tsv_words": len(groups), "rows": len(rows), "dedup_collisions": collisions,
            "rows_with_extra_levels": sum(1 for r in rows if r["extra_levels"]),
            "rows_with_homograph_index": sum(1 for r in rows if r["homograph"]),
            "rows_without_pos": sum(1 for r in rows if not r["pos"])}
    return rows, info


def is_pure_variant(e: dict) -> bool:
    return not e["senses"]


def link_rows(ds, rows: list[dict], pin_field=("canonical", "simp", "trad")) -> dict:
    """Resolve each row to its primary entry and `also` entries (PIPE-041). Works on Mandarin entries."""
    by_simp: dict[str, list[dict]] = defaultdict(list)
    for e in ds.entries:
        if not e["unihan"]:
            by_simp[e["simp"]].append(e)
    unresolved = []
    reresolved = []
    multi = []
    via_redirect = 0
    for r in rows:
        pin = parse_pinyin(respace_numbered(r["cedict_pinyin"]))[1]
        trads = r["cedict_trad"].split("/")
        same = [e for e in by_simp.get(r["simp"], ()) if e["canonical"] == pin]
        cands = [e for e in same if e["trad"] in trads] or same
        if not cands:
            # the parser resolved against another CC-CEDICT release: re-resolve against the pinned one
            # through an alternative reading of the entry, then a unique tone-insensitive match
            pool = [e for e in by_simp.get(r["simp"], ()) if e["trad"] in trads] or by_simp.get(r["simp"], [])
            alt = [e for e in pool if any(a["c"] == pin for a in e["alt"])]
            toneless = [e for e in pool if re.sub(r"\d", "", e["canonical"]) == re.sub(r"\d", "", pin)]
            cands = alt or (toneless if len(toneless) == 1 else [])
            if cands:
                same = cands
                reresolved.append({"row_id": r["row_id"], "word": r["simp"], "parser_pinyin": r["cedict_pinyin"],
                                   "entry_pinyin": cands[0]["canonical"], "via": "alt_reading" if alt else "toneless"})
        if not cands:
            unresolved.append({"row_id": r["row_id"], "word": r["simp"], "pinyin": r["cedict_pinyin"],
                               "trad": r["cedict_trad"]})
            continue
        cands.sort(key=lambda e: (e["proper"], is_pure_variant(e), -len(e["senses"]), e["id"]))
        primary = cands[0]
        also = [e for e in same if e is not primary and not e["proper"] and not is_pure_variant(e)]
        r["_entry"] = primary
        r["_also"] = also
        if is_pure_variant(primary):
            via_redirect += 1
        if also:
            multi.append({"row_id": r["row_id"], "word": r["simp"], "primary": primary["trad"],
                          "also": [e["trad"] for e in also]})
    return {"unresolved": unresolved, "multi_entry_links": multi, "linked_to_pure_variant": via_redirect,
            "reresolved": reresolved}


def apply_levels(rows: list[dict], entry_of) -> None:
    """Fill entry['hsk'] from the rows; `entry_of(row)` yields the entries linked by the row."""
    acc: dict[int, dict] = {}
    for r in rows:
        for e in entry_of(r):
            slot = acc.setdefault(e["id"], {"e": e, "main": {}, "extra": {}})
            cur = slot["main"].setdefault(r["level"], [])
            for t in r["pos"]:
                if t not in cur:
                    cur.append(t)
            for x in r["extra_levels"]:
                cur = slot["extra"].setdefault(x["l"], [])
                for t in x["pos"]:
                    if t not in cur:
                        cur.append(t)
    for slot in acc.values():
        hsk = [{"l": lv, "pos": p, "x": 0} for lv, p in sorted(slot["main"].items())]
        hsk += [{"l": lv, "pos": p, "x": 1} for lv, p in sorted(slot["extra"].items()) if lv not in slot["main"]]
        slot["e"]["hsk"] = hsk


def entry_sort_key(e: dict) -> str:
    toneless = " ".join(e["syllables"])
    return sort_reading(toneless) + "\t" + sort_reading(e["canonical"])


def order_rows(rows: list[dict], key) -> None:
    for i, r in enumerate(sorted(rows, key=key)):
        r["ord"] = i


def propagate_via_variants(ds) -> dict:
    """DATA-193 / PIPE-082: undirected variant graph, at most two hops."""
    adj: dict[int, set[int]] = defaultdict(set)
    kinds = {"variant_of", "old_variant_of", "erhua_variant_of"}
    for e in ds.entries:
        if e["unihan"]:
            continue
        targets = []
        r = e["redirect"]
        if r and r["resolved"]:
            targets.append(r["target"])
        for n in e["notes"]:
            if n["kind"] in kinds:
                targets += [ref["id"] for ref in n["refs"] if ref.get("id") is not None]
        for t in targets:
            if t != e["id"] and not ds.entries[t]["unihan"]:
                adj[e["id"]].add(t)
                adj[t].add(e["id"])
    best: dict[int, tuple] = {}      # entry id -> (min level, hops, source id)
    reach: dict[int, set] = defaultdict(set)
    hop3 = set()
    for src in ds.entries:
        if not src["hsk"]:
            continue
        lv = min(x["l"] for x in src["hsk"])
        dist = {src["id"]: 0}
        dq = deque([src["id"]])
        while dq:
            u = dq.popleft()
            if dist[u] == 3:
                continue
            for v in sorted(adj.get(u, ())):
                if v in dist:
                    continue
                dist[v] = dist[u] + 1
                dq.append(v)
        for v, d in dist.items():
            if d == 0 or ds.entries[v]["hsk"]:
                continue
            if d == 3:
                hop3.add(v)
                continue
            reach[v].add(tuple(x["l"] for x in src["hsk"]))
            cand = (lv, d, src["id"])
            if v not in best or cand < best[v]:
                best[v] = cand
    hops = defaultdict(int)
    single = erhua = 0
    for v, (lv, d, sid) in best.items():
        e = ds.entries[v]
        e["hsk_via"] = [dict(x) for x in ds.entries[sid]["hsk"]]
        hops[d] += 1
        if len(e["simp"]) == 1:
            single += 1
        if e["simp"].endswith("儿") and len(e["simp"]) > 1:
            erhua += 1
    return {"entries": len(best), "per_hop": dict(sorted(hops.items())), "single_characters": single,
            "words": len(best) - single, "erhua_forms": erhua,
            "conflicts": sum(1 for v in best if len(reach[v]) > 1),
            "hop3_not_propagated": len(hop3 - set(best))}


def read_chars(path: Path) -> list[dict]:
    out = []
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            out.append({"ch": r["char"], "level": _level(r["recognition_level"]),
                        "writing": 1 if r["writing"].strip().lower() == "true" else 0,
                        "writing_band": r["writing_level"].strip()})
    return out
