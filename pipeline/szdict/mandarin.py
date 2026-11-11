"""Mandarin dataset build (PIPE-020…030, PIPE-040…046, PIPE-080, PIPE-082)."""
from __future__ import annotations

from collections import Counter, defaultdict

from . import hsk as hskmod
from . import unihan as uh
from .cedict import read_cedict, read_header
from .config import Config
from .core import (BLOCK_LABEL, RSRC, BuildError, Dataset, Resolver, assign_natural_keys,
                   build_alt_readings, build_merge_groups, count_refs, finish_redirects, iter_refs,
                   make_redirects, natural_key)
from .norm import fold
from .roman import (load_aliases, marked_to_numbered, parse_pinyin, pinyin_inventory,
                    respace_numbered)
from .senses import parse_gloss


def pinyin_abbrevs(syllables: list[str]) -> list[tuple[str, str]]:
    """DATA-150: first letter plus zh/ch/sh; i, u, v(ü) are never abbreviations."""
    out = []
    for s in syllables:
        if s[0] in "iuü":
            continue
        out.append((s[0], s))
        if s[:2] in ("zh", "ch", "sh") and len(s) > 2:
            out.append((s[:2], s))
    return out


def add_cedict_entry(ds: Dataset, trad: str, simp: str, reading: str, glosses: list[str], source: str) -> dict:
    display, canonical, syl, tones, proper, full, cased = parse_pinyin(reading)
    senses, notes = [], []
    for g in glosses:
        s, n = parse_gloss(g, source, ds.script)
        senses += s
        notes += n
    kind = "hsk_patch" if source == "hsk_patch" else "mandarin"
    blocks = []
    if senses:
        blocks = [{"kind": kind, "label": BLOCK_LABEL[kind], "first": 0, "count": len(senses),
                   "collapsed": False,
                   "readings": [{"d": display, "c": canonical, "first": 0, "count": len(senses)}]}]
    return ds.add(trad=trad, simp=simp, display=display, canonical=canonical, cased=cased,
                  syllables=syl, tones=tones, proper=proper, full_parse=full, rsrc=RSRC[source],
                  senses=senses, notes=notes, blocks=blocks)


def load_frequency(cfg: Config) -> tuple[dict[str, int], int]:
    freq: dict[str, int] = {}
    for line in (cfg.pinned / "freq" / "zh_cn_freq.tsv").read_text("utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        w, c = line.split("\t")
        freq[w] = int(c)
    return freq, 81_775_831          # corpus total of the FrequencyWords zh_cn list (PIPE-011)


def add_unihan_entries(ds: Dataset, cfg: Config, reading_field: str) -> dict:
    """DATA-190: Unihan-only entries for ideographs with a kDefinition and no dictionary headword."""
    data = uh.read_unihan(cfg.src("unihan"))
    headwords = set()
    for e in ds.entries:
        headwords.add(e["simp"])
        headwords.add(e["trad"])
    chars = sorted(ch for ch, f in data.items() if "kDefinition" in f and ch not in headwords
                   and uh.is_ideograph(ch))
    if cfg.fixture:
        chars = [c for c in chars if c in cfg.fixture_chars]
    candidates = set(chars)
    stats = Counter(ideographs_read=len(data), with_definition=sum(1 for f in data.values() if "kDefinition" in f))
    for ch in chars:
        f = data[ch]
        senses = uh.definition_senses(f["kDefinition"], ds.script)
        if not senses:
            stats["empty_definition"] += 1
            continue
        notes = []
        if reading_field == "kMandarin":
            raw = []
            if "kMandarin" in f:
                vals = f["kMandarin"].split()
                raw = [(marked_to_numbered(v, 1), "taiwan_pr") for v in vals]
            elif "kHanyuPinyin" in f:
                raw = [(marked_to_numbered(v, 1), "also_pr") for v in uh.hanyu_pinyin_readings(f["kHanyuPinyin"])]
            stats["readings_dropped"] += sum(1 for r, _k in raw if r is None)
            raw = [(r, k) for r, k in raw if r is not None]
            parse = lambda r: parse_pinyin(r)[:6]                       # noqa: E731
        else:
            from .roman import parse_jyutping
            vals = f.get("kCantonese", "").split()
            raw = []
            for v in vals:
                p = parse_jyutping(v)
                if p[4]:
                    raw.append((v, "also_pr"))
                else:
                    stats["readings_dropped"] += 1
            parse = lambda r: parse_jyutping(r) + (False,)              # noqa: E731
        trad = simp = ch
        sv = [c for c in uh.variant_chars(f.get("kSimplifiedVariant", "")) if c != ch]
        tv = [c for c in uh.variant_chars(f.get("kTraditionalVariant", "")) if c != ch]
        if len(sv) == 1 and sv[0] not in headwords and sv[0] not in candidates:
            simp = sv[0]
        if len(tv) == 1 and tv[0] not in headwords and tv[0] not in candidates:
            trad = tv[0]
        if raw:
            if reading_field == "kMandarin":
                display, canonical, syl, tones, _p, _full, cased = parse_pinyin(raw[0][0])
            else:
                display, canonical, syl, tones, _full = parse(raw[0][0])[:5]
                cased = canonical
            rsrc = RSRC["unihan"]
            seen = {raw[0][0]}
            for r, kind in raw[1:]:
                if r in seen:
                    continue
                seen.add(r)
                d = parse(r)[0]
                notes.append({"kind": kind, "label": kind, "refs": [], "reading": d, "extra": None,
                              "_readings": [r]})
                stats["alt_readings"] += 1
        else:
            display = canonical = cased = ""
            syl, tones = (), ()
            rsrc = RSRC["none"]
            stats["without_reading"] += 1
        blocks = [{"kind": "unihan", "label": BLOCK_LABEL["unihan"], "first": 0, "count": len(senses),
                   "collapsed": False}]
        ds.add(trad=trad, simp=simp, display=display, canonical=canonical, cased=cased, syllables=syl,
               tones=tones, rsrc=rsrc, unihan=True, full_parse=False, senses=senses, notes=notes, blocks=blocks)
        stats["entries"] += 1
        stats["senses"] += len(senses)
    return dict(sorted(stats.items()))


def resolve_all(ds: Dataset) -> Resolver:
    res = Resolver(ds.entries)
    for e in ds.entries:
        for ref in iter_refs(e):
            if ref.get("rom", "pinyin") != "pinyin":
                continue
            tid = res.resolve(ref, exclude=e["id"])
            if tid is not None:
                ref["id"] = tid
    return res


def build_mandarin(cfg: Config) -> Dataset:
    ds = Dataset("mandarin")
    rep = ds.report
    hyg: dict = {}
    keep = (lambda t, s: s in cfg.fixture_words or t in cfg.fixture_words) if cfg.fixture else (lambda t, s: True)

    # --- CC-CEDICT
    cedict_path = cfg.refs / "cedict_ts.u8"
    ds.cedict_header = read_header(cedict_path)
    seen_keys: dict[str, int] = {}
    for _n, trad, simp, reading, glosses in read_cedict(cedict_path, hyg):
        if keep(trad, simp):
            e = add_cedict_entry(ds, trad, simp, reading, glosses, "cedict")
            seen_keys[natural_key(trad, simp, e["cased"])] = e["id"]
    rep["cedict"] = {"entries": len(ds.entries), **hyg,
                     "entries_without_senses": sum(1 for e in ds.entries if not e["senses"])}

    # --- HSK parser patch (PIPE-044)
    patch_rep = {"entries": 0, "skipped_already_in_cedict": [], "duplicate_patch_lines": 0}
    phyg: dict = {}
    for _n, trad, simp, reading, glosses in read_cedict(cfg.pinned / "hsk" / "cedict_patch.u8", phyg):
        if not keep(trad, simp):
            continue
        reading = reading.split(";")[0].strip()
        cased = parse_pinyin(reading)[6]
        k = natural_key(trad, simp, cased)
        if k in seen_keys:
            if ds.entries[seen_keys[k]]["rsrc"] == RSRC["cedict"]:
                patch_rep["skipped_already_in_cedict"].append(f"{trad} {simp} [{reading}]")
            else:
                patch_rep["duplicate_patch_lines"] += 1
            continue
        e = add_cedict_entry(ds, trad, simp, reading, glosses, "hsk_patch")
        seen_keys[k] = e["id"]
        patch_rep["entries"] += 1
    rep["hsk_patch"] = patch_rep
    ds.n_dictionary = len(ds.entries)

    # --- Unihan fallback entries (PIPE-080)
    rep["unihan"] = add_unihan_entries(ds, cfg, "kMandarin")
    ds.radical_forms = uh.radical_form_targets(cfg.src("unihan"), cfg.src("ucd_equivalent_unified_ideograph"),
                                               cfg.src("ucd_cjk_radicals"))
    rep["unihan"]["radical_forms_mapped"] = len(ds.radical_forms)

    assign_natural_keys(ds)
    make_redirects(ds)
    resolve_all(ds)
    finish_redirects(ds)
    count_refs(ds)
    build_alt_readings(ds, lambda raw: parse_pinyin(respace_numbered(raw))[:4] + (parse_pinyin(respace_numbered(raw))[5],))
    build_merge_groups(ds)

    # note / tag statistics (DATA-062, PIPE-079)
    nk, tg = Counter(), Counter()
    with_note = 0
    for e in ds.entries:
        if e["notes"] or e["redirect"]:
            with_note += 1
        for n in e["notes"]:
            nk[n["kind"]] += 1
        if e["redirect"]:
            nk["redirect_" + e["redirect"]["kind"]] += 1
        for s in e["senses"]:
            for t in s["tags"]:
                tg[t] += 1
    rep["notes"] = {"by_kind": dict(sorted(nk.items())), "entries_with_note": with_note}
    rep["tags"] = dict(sorted(tg.items()))
    rep["reading_parse_exceptions"] = sum(
        1 for e in ds.entries[:ds.n_dictionary] if not e["full_parse"])

    # --- syllable tables (DATA-070, DATA-150)
    ds.syllables = sorted(pinyin_inventory())
    ds.aliases = load_aliases("pinyin_aliases.tsv")
    ds.abbrevs = pinyin_abbrevs(ds.syllables)
    ds.freq, ds.total_freq = load_frequency(cfg)
    if cfg.fixture:
        forms = {e["simp"] for e in ds.entries} | {e["trad"] for e in ds.entries}
        ds.freq = {w: c for w, c in ds.freq.items() if w in forms}
    return ds


def build_hsk(ds: Dataset, cfg: Config) -> list[dict]:
    """Link HSK rows and characters to Mandarin entries; returns the rows (reused by Cantonese)."""
    rows, info = hskmod.read_rows(cfg.pinned / "hsk" / "hsk_word_list.tsv")
    if cfg.fixture:
        rows = [r for r in rows if r["simp"] in cfg.fixture_words]
    link = hskmod.link_rows(ds, rows)
    info.update({"unresolved_rows": link["unresolved"], "multi_entry_links": link["multi_entry_links"],
                 "linked_to_pure_variant": link["linked_to_pure_variant"],
                 "reresolved_against_pinned_cedict": link["reresolved"]})
    rows = [r for r in rows if "_entry" in r]
    hskmod.apply_levels(rows, lambda r: [r["_entry"]] + r["_also"])
    for r in rows:
        e = r["_entry"]
        r["entry_id"] = e["id"]
        r["also_entry_ids"] = [x["id"] for x in r["_also"]]
        r["trad"] = e["trad"]
        r["sort_key"] = hskmod.entry_sort_key(e)
    hskmod.order_rows(rows, lambda r: (r["level"], r["sort_key"], r["simp"], r["row_id"]))
    info["per_level"] = dict(sorted(Counter(r["level"] for r in rows).items()))
    info["linked_patch_entries"] = sorted({r["simp"] for r in rows if r["_entry"]["rsrc"] == RSRC["hsk_patch"]})
    info["patch_entries_without_row"] = sorted(
        e["simp"] for e in ds.entries if e["rsrc"] == RSRC["hsk_patch"] and not e["hsk"])
    info["propagation"] = hskmod.propagate_via_variants(ds)
    ds.hsk_rows = rows
    ds.report["hsk"] = info
    return rows


def build_hsk_chars(ds: Dataset, cfg: Config, sort_key_of=None) -> None:
    chars = hskmod.read_chars(cfg.pinned / "hsk" / "hsk_char_list.tsv")
    by_simp: dict[str, list[dict]] = defaultdict(list)
    for e in ds.entries:
        if len(e["simp"]) == 1:
            by_simp[e["simp"]].append(e)
    row_entry: dict[str, int] = {}
    for r in sorted(ds.hsk_rows, key=lambda r: (r["level"], r["row_id"])):
        row_entry.setdefault(r["simp"], r["entry_id"])
    missing = []
    out = []
    via_row = 0
    for c in chars:
        ch = c["ch"]
        cands = by_simp.get(ch, [])
        if cfg.fixture and not cands:
            continue
        if not cands:
            missing.append(ch)
            continue
        if ch in row_entry and len(ds.entries[row_entry[ch]]["simp"]) == 1:
            e = ds.entries[row_entry[ch]]
            via_row += 1
        else:
            e = min(cands, key=lambda e: (e["freq_rank"], e["rsrc"] == RSRC["none"], e["proper"],
                                          not e["senses"], -len(e["senses"]), e["id"]))
        c["entry_id"] = e["id"]
        c["sort_key"] = (sort_key_of or hskmod.entry_sort_key)(e)
        c["_nokey"] = 1 if e["rsrc"] == RSRC["none"] else 0
        out.append(c)
    for i, c in enumerate(sorted(out, key=lambda c: (c["level"], c["_nokey"], c["sort_key"], c["ch"]))):
        c["ord"] = i
    ds.hsk_chars = out
    ds.report.setdefault("hsk", {})["characters"] = {
        "recognition": len(out), "writing": sum(c["writing"] for c in out),
        "per_level": dict(sorted(Counter(c["level"] for c in out).items())),
        "writing_bands": dict(sorted(Counter(c["writing_band"] for c in out if c["writing"]).items())),
        "primary_from_hsk_row": via_row, "without_entry": missing}
