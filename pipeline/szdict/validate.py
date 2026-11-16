"""Validation gates (DATA-200) run against the written files."""
from __future__ import annotations

import json
import math
import sqlite3
from collections import Counter

from .config import Config
from .core import RSRC, UNRANKED, content_hash, expand_body
from .enc import body_dict, derive_reading_tokens, read_delta_list, read_varints, unzjson
from .hsk import CHAR_COUNTS, LEVEL_COUNTS, WRITING_COUNTS
from .norm import matching_key
from .roman import parse_pinyin
from .senses import labels

META_KEYS = ["schema_version", "dataset_id", "dataset_version", "pipeline_commit", "input_hash", "built_at",
             "content_hash", "language", "romanization", "tone_count", "entry_count", "max_headword_len",
             "max_word_len", "total_frequency", "has_frequency_data", "has_hsk", "sources", "asset_packs",
             "decomposition_hashes", "has_bigram_model", "has_trigram_model", "lm_source", "abbrev_penalty", "partial_penalty",
             "lm_word_count", "lm_file"]


class Gates:
    def __init__(self) -> None:
        self.results: dict[str, dict] = {}

    def check(self, gate: str, ok: bool, detail=None) -> None:
        r = self.results.setdefault(gate, {"pass": True, "failures": []})
        if not ok:
            r["pass"] = False
            if len(r["failures"]) < 20:
                r["failures"].append(detail if detail is not None else "failed")

    def note(self, gate: str, key: str, value) -> None:
        self.results.setdefault(gate, {"pass": True, "failures": []})[key] = value

    @property
    def ok(self) -> bool:
        return all(r["pass"] for r in self.results.values())


def validate_dataset(cfg: Config, ds_id: str, fixture: bool) -> Gates:
    g = Gates()
    con = sqlite3.connect(f"file:{cfg.out / (ds_id + '.db')}?mode=ro", uri=True)
    lmc = sqlite3.connect(f"file:{cfg.out / (ds_id + '-lm.db')}?mode=ro", uri=True)
    meta = dict(con.execute("SELECT key, value FROM meta"))

    # 1 metadata
    for k in META_KEYS:
        g.check("01_metadata", bool(meta.get(k)) or meta.get(k) in ("0", "[]", "{}"), f"missing meta {k}")
    g.check("01_metadata", meta.get("content_hash") == content_hash(con), "content_hash does not match content")
    g.check("01_metadata", con.execute("PRAGMA user_version").fetchone()[0] == int(meta["schema_version"]),
            "user_version != schema_version")

    # 2 entry count
    n = con.execute("SELECT count(*) FROM entry").fetchone()[0]
    g.check("02_entry_count", n == int(meta["entry_count"]), f"{n} != {meta['entry_count']}")
    ids = {r[0] for r in con.execute("SELECT id FROM entry")}
    max_id = max(ids)

    # 3 entries; 10 reading round trip; 19 unihan; 17 labels
    syl_inv = {r[0] for r in con.execute("SELECT value FROM syllable")}
    label_keys = {r[0] for r in con.execute("SELECT key FROM label WHERE en<>'' AND zh_hans<>'' AND zh_hant<>''")}
    max_len = 0
    parse_exc = none_count = 0
    unihan_forms, dict_forms = set(), set()
    bodies: dict[int, dict] = {}
    hsk_of: dict[int, tuple] = {}
    zdict = body_dict(con)
    for (eid, trad, simp, disp, canon, rsrc, syl, tones, flags, rank, cc, nk, hsk, via, body) in con.execute(
            "SELECT id,trad,simp,reading_display,reading_canonical,reading_source,syllables,tones,flags,"
            "freq_rank,char_count,natural_key,hsk,hsk_via,body FROM entry"):
        if disp is None:                                   # schema version 2 defaults
            disp = canon if ds_id != "mandarin" else parse_pinyin(canon)[0]
            g.check("10_reading_round_trip", ds_id != "mandarin" or parse_pinyin(canon)[5], f"{eid}: NULL display of {canon!r}")
        if syl is None or tones is None:
            d_syl, d_tones = derive_reading_tokens(canon)
            syl, tones = " ".join(d_syl), bytes(d_tones)
        g.check("03_entries", isinstance(nk, bytes) and len(nk) == 8, f"{eid}: natural key is not 8 bytes")
        g.check("03_entries", bool(trad) and bool(simp), f"{eid}: empty form")
        syls = syl.split(" ") if syl else []
        g.check("03_entries", len(syls) == len(tones), f"{eid}: syllables/tones length")
        g.check("03_entries", cc == len(simp), f"{eid}: char_count")
        max_len = max(max_len, len(simp))
        b = expand_body(unzjson(body, zdict), disp, canon)
        bodies[eid] = b
        pos = 0
        for blk in b["blocks"]:
            g.check("03_entries", blk["first"] == pos and blk["count"] > 0, f"{eid}: block not contiguous")
            g.check("17_labels", blk["label"] in label_keys, f"block label {blk['label']}")
            pos += blk["count"]
        g.check("03_entries", pos == len(b["senses"]), f"{eid}: blocks do not cover senses")
        for s in b["senses"]:
            g.check("03_entries", bool(s["t"].strip()), f"{eid}: empty sense")
            for t in s["tags"]:
                g.check("17_labels", t in label_keys, f"tag {t}")
            for seg in s["seg"]:
                if seg["k"] == "ref" and "id" in seg:
                    g.check("05_references", seg["id"] in ids, f"{eid}: ref id {seg['id']}")
        for nt in b["notes"]:
            g.check("17_labels", nt["label"] in label_keys, f"note label {nt['label']}")
            for ref in nt["refs"]:
                if "id" in ref:
                    g.check("05_references", ref["id"] in ids, f"{eid}: note ref id")
        red = b.get("redirect")
        if red:
            g.check("05_references", (red["target"] in ids) if red["resolved"] else red["target"] is None,
                    f"{eid}: redirect target")
        unihan = bool(flags & 4)
        if rsrc == RSRC["none"]:
            none_count += 1
            g.check("03_entries", not syls and not disp, f"{eid}: none entry with reading")
        else:
            if any(s not in syl_inv for s in syls):
                parse_exc += 1
            if ds_id == "mandarin":
                regen = parse_pinyin(canon)[0]
                g.check("10_reading_round_trip", regen.lower() == disp.lower(), f"{eid}: {canon} -> {regen} != {disp}")
            else:
                g.check("10_reading_round_trip", canon == disp, f"{eid}: jyutping display")
        if unihan:
            unihan_forms.update((trad, simp))
            g.check("19_unihan", len(b["senses"]) >= 1 and len(b["blocks"]) == 1 and b["blocks"][0]["kind"] == "unihan",
                    f"{eid}: unihan block")
            g.check("19_unihan", rank == UNRANKED, f"{eid}: unihan ranked")
        else:
            dict_forms.update((trad, simp))
            g.check("19_unihan", all(blk["kind"] != "unihan" for blk in b["blocks"]), f"{eid}: unihan block on dictionary entry")
        h, v = json.loads(hsk or "[]"), json.loads(via or "[]")
        hsk_of[eid] = (h, v)
        g.check("08_hsk", not (h and v), f"{eid}: both hsk and hsk_via")
    g.check("19_unihan", not (unihan_forms & dict_forms), f"shared headwords: {sorted(unihan_forms & dict_forms)[:5]}")
    g.note("03_entries", "reading_parse_exceptions", parse_exc)
    g.note("03_entries", "reading_source_none", none_count)
    g.check("03_entries", parse_exc <= max(50, n // 100), f"{parse_exc} reading parse exceptions")

    # 4 postings
    for table, col in (("substr_idx", "key"), ("char_idx", "ch")):
        for key, blob in con.execute(f"SELECT {col}, postings FROM {table}"):
            lst = read_delta_list(blob) if table == "substr_idx" else read_varints(blob)
            if table == "substr_idx":
                g.check("04_postings", all(a < b for a, b in zip(lst, lst[1:])), f"{table} {key}: not ascending")
            g.check("04_postings", bool(lst) and max(lst) <= max_id and all(i in ids for i in lst[:50]), f"{table} {key}: bad id")
    for tok, blob in con.execute("SELECT token, postings FROM en_token"):
        vals = read_varints(blob)
        eid = 0
        prev = (-1, -1)
        for i in range(0, len(vals), 2):
            eid += vals[i]
            sense = vals[i + 1] >> 2
            g.check("04_postings", (eid, sense) > prev, f"en_token {tok}: order")
            prev = (eid, sense)
            b = bodies.get(eid)
            g.check("04_postings", b is not None and sense < len(b["senses"]), f"en_token {tok}: ({eid},{sense})")
        g.check("15_normalisation", tok == matching_key(tok), f"en token {tok!r}")

    # 5/6 merge groups (inside-words rows are computed by the app: no `within` table in schema version 2)
    for gid, script, members, variants in con.execute("SELECT id, script, members, variants FROM merge_group"):
        m = read_varints(members)
        g.check("05_references", all(i in ids for i in m), f"merge group {gid}")
        g.check("06_merge_groups", len(m) >= 2 and len(set(json.loads(variants))) >= 1, f"merge group {gid}")

    # 7 lengths
    g.check("07_lengths", int(meta["max_headword_len"]) == max_len, f"max_headword_len {meta['max_headword_len']} != {max_len}")
    g.check("07_lengths", int(meta["max_word_len"]) <= int(meta["max_headword_len"]) or fixture, "max_word_len > max_headword_len")

    # 8 HSK
    rows = con.execute("SELECT row_id, ord, level, simp, homograph, entry_id, also_entry_ids, sort_key FROM hsk_row ORDER BY ord").fetchall()
    per_level = Counter(r[2] for r in rows)
    if not fixture:
        g.check("08_hsk", dict(per_level) == LEVEL_COUNTS, f"per-level counts {dict(sorted(per_level.items()))}")
    g.note("08_hsk", "per_level", dict(sorted(per_level.items())))
    prev = None
    for row_id, ord_, level, simp, homograph, entry_id, also, sort_key in rows:
        g.check("08_hsk", entry_id in ids, f"row {row_id} unlinked")
        for a in [entry_id] + json.loads(also):
            g.check("08_hsk", a in ids and any(x["l"] == level and x["x"] == 0 for x in hsk_of[a][0]),
                    f"row {row_id}: entry {a} lacks level {level}")
        key = (level, ord_)
        g.check("08_hsk", prev is None or key > prev, f"row {row_id}: storage order")
        prev = key
    chars = con.execute("SELECT ch, level, writing, writing_band, entry_id FROM hsk_char").fetchall()
    if not fixture:
        g.check("08_hsk", dict(Counter(c[1] for c in chars)) == CHAR_COUNTS, "recognition character counts")
        g.check("08_hsk", dict(Counter(c[3] for c in chars if c[2])) == WRITING_COUNTS, "writing character counts")
    single = {r[0]: r[1] for r in con.execute("SELECT id, simp FROM entry WHERE char_count = 1")}
    for ch, _lv, _w, _b, eid in chars:
        g.check("08_hsk", single.get(eid) == ch, f"hsk_char {ch} -> {eid}")

    # 9 popular
    pop = con.execute("SELECT p.entry_id, e.char_count FROM popular p LEFT JOIN entry e ON e.id = p.entry_id ORDER BY rank").fetchall()
    g.check("09_popular", (len(pop) == 50 or fixture) and len(pop) > 0 and all(c is not None and c >= 2 for _i, c in pop),
            f"popular list has {len(pop)} rows")

    # 14 language model
    cb = {k: n_ for k, n_ in lmc.execute("SELECT kind, count(*) FROM lm_codebook GROUP BY kind")}
    g.check("14_language_model", all(v <= 256 for v in cb.values()) and 0 in cb, f"codebooks {cb}")
    g.check("14_language_model", all(math.isfinite(v) for (v,) in lmc.execute("SELECT value FROM lm_codebook")), "non-finite codebook value")
    lm_rows = dict((e, (w, u)) for e, w, u in lmc.execute("SELECT entry_id, lm_word, lm_unigram FROM lm_entry"))
    nwords = int(meta["lm_word_count"])
    flags = dict(con.execute("SELECT id, flags FROM entry"))
    for eid in ids:
        w = lm_rows.get(eid)
        if flags[eid] & 4:
            g.check("14_language_model", w is None or w[0] == -1, f"unihan entry {eid} has LM word")
        else:
            g.check("14_language_model", w is not None and 0 <= w[0] < nwords and w[1] < cb.get(0, 0), f"entry {eid} lacks LM word")
    nsyl = con.execute("SELECT count(*) FROM syllable").fetchone()[0]
    for key, blob in lmc.execute("SELECT key, postings FROM abbr_idx"):
        g.check("15_normalisation", key == matching_key(key), f"abbr key {key!r}")
        p = 0
        cnt, p = _rv(blob, p)
        for _ in range(cnt):
            eid, p = _rv(blob, p)
            wid, p = _rv(blob, p)
            _code, alt, ns = blob[p], blob[p + 1], blob[p + 2]
            p += 3
            for _s in range(ns):
                si, p = _rv(blob, p)
                g.check("14_language_model", si < nsyl, f"abbr {key}: syllable idx")
            g.check("14_language_model", eid in ids and wid < nwords and not flags[eid] & 4 and ns == len(key) and alt <= 2,
                    f"abbr {key}: posting ({eid},{wid})")
        g.check("14_language_model", p == len(blob), f"abbr {key}: trailing bytes")
    nbig = 0
    for left, blob in lmc.execute("SELECT left, block FROM bigram"):
        p = 1
        cnt, p = _rv(blob, p)
        acc = 0
        for i in range(cnt):
            d, p = _rv(blob, p)
            acc = d if i == 0 else acc + d
            g.check("14_language_model", acc < nwords, f"bigram {left}: successor {acc}")
        g.check("14_language_model", left < nwords and len(blob) == p + cnt, f"bigram {left}: block length")
        nbig += cnt
    g.check("14_language_model", (meta["has_bigram_model"] == "1") == (nbig > 0), "has_bigram_model mismatch")
    g.note("14_language_model", "bigrams", nbig)
    lm_meta = dict(lmc.execute("SELECT key, value FROM meta"))
    g.check("14_language_model", lm_meta.get("dataset_version") == meta["dataset_version"], "LM dataset_version mismatch")
    _check_combination_model(g, lmc, meta, lm_meta, cb, nwords)

    # 15 normalisation of the remaining keys; 21 alternative readings
    for key, kind in con.execute("SELECT DISTINCT key, kind FROM headword WHERE kind = 2"):
        g.check("15_normalisation", key == matching_key(key), f"headword key {key!r}")
    for (key,) in con.execute("SELECT DISTINCT key FROM reading"):
        g.check("15_normalisation", key == matching_key(key), f"reading key {key!r}")
    for (key,) in con.execute("SELECT key FROM substr_idx"):
        g.check("15_normalisation", key == matching_key(key) or key.strip() != key, f"substr key {key!r}")
    for key, eid, alt, ordinal in con.execute("SELECT key, entry_id, alt, alt_note FROM reading WHERE alt > 0"):
        alts = bodies[eid].get("alt", [])
        ok = ordinal < len(alts) and alts[ordinal]["kind"] == ("taiwan_pr" if alt == 2 else "also_pr")
        ok = ok and any(nt["kind"] == alts[ordinal]["kind"] for nt in bodies[eid]["notes"]) if ok else False
        g.check("21_alt_readings", ok and all(s in syl_inv for s in key.split(" ")), f"alt reading {key} of {eid}")

    # 17 labels: POS tokens
    pos_tokens = {r[0] for r in con.execute("SELECT token FROM hsk_pos_label")}
    for h, v in hsk_of.values():
        for x in h + v:
            for t in x["pos"]:
                g.check("17_labels", t in pos_tokens, f"POS token {t!r}")
    g.check("17_labels", {r["key"] for r in labels().rows} <= label_keys, "label rows incomplete")

    # 18 Cantonese
    if ds_id == "cantonese":
        g.check("18_cantonese", con.execute("SELECT count(*) FROM entry WHERE freq_rank < 1000000").fetchone()[0] > 0, "no ranked entry")
        bad = con.execute("SELECT count(*) FROM entry WHERE (flags & 2) AND trad IN ('佢嘅','我嘅','你嘅','我哋嘅','你哋嘅','佢哋嘅')").fetchone()[0]
        g.check("18_cantonese", bad == 0, "segmentable pronoun+嘅 headword")
    con.close()
    lmc.close()
    return g


def _rv(buf: bytes, p: int) -> tuple[int, int]:
    n = shift = 0
    while True:
        b = buf[p]
        p += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, p
        shift += 7


def validate_all(cfg: Config, report: dict, only: str | None = None) -> bool:
    ok = True
    for ds_id in ("mandarin", "cantonese"):
        if only and only != ds_id:
            continue
        if not (cfg.out / f"{ds_id}.db").exists():
            continue
        g = validate_dataset(cfg, ds_id, cfg.fixture)
        report["gates"][ds_id] = dict(sorted(g.results.items()))
        for name, r in sorted(g.results.items()):
            if not r["pass"]:
                print(f"GATE FAIL {ds_id} {name}: {r['failures'][:3]}", flush=True)
        ok = ok and g.ok
    return ok


def _check_combination_model(g, lmc, meta: dict, lm_meta: dict, cb: dict, nwords: int) -> None:
    """Gate 14, combination model (DATA-156): `unigram2` / `bigram2` / `trigram` blocks decode to word
    ids below `lm_word_count`, ascending, with their codebooks (kinds 3…7) present; the
    `has_trigram_model` flags of both files match the presence of the tables."""
    tables = {r[0] for r in lmc.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
    present = {"unigram2", "bigram2", "trigram"} <= tables
    g.check("14_language_model", (meta.get("has_trigram_model", "0") == "1") == present
            and (lm_meta.get("has_trigram_model", "0") == "1") == present, "has_trigram_model mismatch")
    if not present:
        return
    g.check("14_language_model", all(k in cb for k in (3, 4, 5, 6, 7)), f"combination codebooks {cb}")
    blocks = lmc.execute("SELECT block, length(codes) FROM unigram2 ORDER BY block").fetchall()
    g.check("14_language_model", [b for b, _n in blocks] == list(range((nwords + 255) >> 8))
            and sum(n for _b, n in blocks) == nwords, "unigram2 blocks")
    nb = 0
    ok = True
    for left, blob in lmc.execute("SELECT left, block FROM bigram2"):
        cnt, p = _rv(blob, 1)
        acc = -1
        for i in range(cnt):
            d, p = _rv(blob, p)
            nxt = d if i == 0 else acc + d
            ok = ok and nxt > acc and nxt < nwords
            acc = nxt
        ok = ok and left < nwords and len(blob) == p + cnt and cnt > 0
        nb += cnt
    g.check("14_language_model", ok, "bigram2 blocks")
    nt = nctx = 0
    ok = True
    prev_key = -1
    for key, blob in lmc.execute("SELECT key, block FROM trigram ORDER BY key"):
        w1, w2 = key >> 17, key & 0x1FFFF
        ok = ok and key > prev_key and w1 < nwords
        prev_key = key
        p = 0
        first = True
        while p < len(blob):
            d, p = _rv(blob, p)
            ok = ok and (d == 0 if first else d > 0)
            w2 += d
            first = False
            p += 1                                  # backoff code
            cnt, p = _rv(blob, p)
            acc = -1
            for i in range(cnt):
                d, p = _rv(blob, p)
                nxt = d if i == 0 else acc + d
                ok = ok and nxt > acc and nxt < nwords
                acc = nxt
            p += cnt
            ok = ok and w2 < nwords and cnt > 0
            nt += cnt
            nctx += 1
        ok = ok and p == len(blob)
    g.check("14_language_model", ok, "trigram pages")
    g.note("14_language_model", "combination_bigrams", nb)
    g.note("14_language_model", "combination_trigrams", nt)
    g.note("14_language_model", "combination_trigram_contexts", nctx)
