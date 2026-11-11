"""Cantonese dataset build (PIPE-050…056, PIPE-074, PIPE-077, PIPE-078, DATA-180…189)."""
from __future__ import annotations

import copy
import io
import math
import re
import unicodedata
import zipfile
from collections import Counter, defaultdict

from . import hsk as hskmod
from . import lm as lmmod
from . import unihan as uh
from .config import Config
from .core import (BLOCK_LABEL, RSRC, Dataset, Resolver, assign_frequency, assign_natural_keys,
                   build_alt_readings, build_merge_groups, count_refs, finish_redirects, iter_refs)
from .mandarin import add_unihan_entries, build_hsk_chars
from .norm import HANZI_CLASS, en_tokens, fold, is_hanzi
from .roman import (jyutping_inventory, load_aliases, marked_to_numbered, parse_jyutping, parse_pinyin,
                    respace_numbered, sort_reading)
from .senses import parse_gloss, plain_text

CANTO_RE = re.compile(r"(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+\{(.*?)\}\s*(?:/(.*)/)?\s*(#.*)?$")
_H = HANZI_CLASS
PLECO_RE = re.compile(f"([{_H}]+)([a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ']+) \\[([{_H}]+)\\]", re.I)
REG_RE = re.compile(r"\s*\[(colloquial|written and colloquial|written)\]\s*$", re.I)
REG_TAG = {"colloquial": "colloquial", "written and colloquial": "written_colloquial", "written": "written"}
POS_RE = re.compile(r"(?:^|(?<=;\s)|(?<=,\s))\((noun|verb|adjective|adverb|adj|adv)\)\s*", re.I)
POS_LEAD_RE = re.compile(r"^(n|v|adj|adv|noun|verb|adjective|adverb)[.;]\s+", re.I)
POS_TAG = {"noun": "pos_noun", "n": "pos_noun", "verb": "pos_verb", "v": "pos_verb", "adjective": "pos_adjective",
           "adj": "pos_adjective", "adverb": "pos_adverb", "adv": "pos_adverb"}
NUM_RE = re.compile(r"(?:^|[;,.]?\s+)(?<![\d.])\(?\d{1,2}[.)]\s*(?=[A-Za-z(\[\"'])")
PRONOUNS = ["我", "你", "佢", "我哋", "你哋", "佢哋", "我地", "你地", "佢地", "邊個", "自己", "人哋", "大家", "妳", "您"]
PRONOUN_GE = {p + "嘅" for p in PRONOUNS}


def jyutping_abbrevs(syllables: list[str]) -> list[tuple[str, str]]:
    """DATA-150: first letter plus the multi-letter initials ng / gw / kw."""
    out = []
    for s in syllables:
        out.append((s[0], s))
        if s[:2] in ("ng", "gw", "kw") and len(s) > 2:
            out.append((s[:2], s))
    return out


def norm_jyutping(raw: str, stats: Counter) -> list[str]:
    """Reading field -> list of alternative readings, each 'jat1 dong2' (PIPE-078)."""
    s = unicodedata.normalize("NFKC", raw)
    if s != raw:
        stats["jyutping_fullwidth_normalised"] += 1
    alts = []
    for part in re.split(r"\s*/\s*", s):
        toks = []
        clean = True
        for tok in re.sub(r"[^A-Za-z0-9]+", " ", part).split():
            if re.fullmatch(r"[a-z]+[1-6]", tok):
                toks.append(tok)
            else:
                clean = False
                toks.extend(t.lower() for t in re.findall(r"[a-z]+[1-6]|[A-Z]+|[a-z]+|\d+", tok))
        if re.search(r"[^A-Za-z0-9\s]", part):
            stats["jyutping_markup_removed"] += 1
        elif not clean:
            stats["jyutping_nonstandard_tokens"] += 1
        if toks:
            alts.append(" ".join(toks))
    return alts


def norm_pinyin_key(raw: str) -> str:
    return parse_pinyin(respace_numbered(re.sub(r"[,，]", " ", raw)))[1]


def read_canto(path, stats: Counter):
    seen = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if not line.strip() or line.startswith("#"):
                continue
            if line in seen:
                stats["duplicate_lines"] += 1
                continue
            seen.add(line)
            m = CANTO_RE.match(line)
            if not m:
                stats["malformed_lines"] += 1
                continue
            trad, simp, pin, jy, body, comment = m.groups()
            if "}" in jy:
                stats["malformed_lines_repaired"] += 1
            if not pin.strip():
                stats["empty_pinyin"] += 1
            yield trad, simp, pin, norm_jyutping(jy, stats), body, bool(comment and "adapted from cc-cedict" in comment)


# ------------------------------------------------------------------------------------------
# CC-Canto sense parsing (PIPE-079 rules 3, 4)

class CantoParser:
    def __init__(self, man: Dataset, stats: Counter):
        self.stats = stats
        self.by_simp: dict[str, list[dict]] = defaultdict(list)
        for e in man.entries:
            if not e["unihan"]:
                self.by_simp[e["simp"]].append(e)

    def _pleco(self, m: re.Match) -> str:
        hz, pin, _br = m.group(1), m.group(2), m.group(3)
        self.stats["pleco_refs"] += 1
        numbered = marked_to_numbered(pin.lower(), len(hz))
        if numbered is None:
            self.stats["pleco_refs_unparsed_pinyin"] += 1
            return hz
        toneless = re.sub(r"\d", "", numbered)
        cands = [e for e in self.by_simp.get(hz, ()) if re.sub(r"\d", "", e["canonical"]) == toneless]
        exact = [e for e in cands if e["canonical"] == numbered] or cands
        trads = sorted({e["trad"] for e in exact})
        if exact:
            numbered = exact[0]["cased"] if len({e["cased"] for e in exact}) == 1 else numbered
        trad = trads[0] if len(trads) == 1 else hz
        return f"{trad}|{hz}[{numbered}]" if trad != hz else f"{hz}[{numbered}]"

    def _numbered(self, text: str) -> list[str]:
        if not re.match(r"\(?1[.)]\s", text):
            return [text]
        parts = [p.strip().rstrip(";").strip() for p in NUM_RE.split(text)]
        self.stats["numbered_sense_glosses"] += 1
        return [p for p in parts if p]

    def adapted(self, body: str):
        body = re.sub(rf"\]\s*/\s*(?=[{_H}]+[a-züà-ǜ]+ \[)", "], ", body)
        senses, notes = [], []
        for g in body.split("/"):
            g = g.strip()
            if not g:
                continue
            extra = []
            m = re.search(r"\s*\bM:\s*(.+)$", g)
            if m:
                extra.append("CL:" + PLECO_RE.sub(self._pleco, m.group(1)).replace(" ", ""))
                g = g[:m.start()].strip()
            g = PLECO_RE.sub(self._pleco, g)
            for part in self._numbered(g) + extra:
                if not part:
                    continue
                s, n = parse_gloss(part, "cccanto_adapted", "tr")
                senses += s
                notes += [x for x in n if x["kind"] not in ("also_pr", "taiwan_pr")]   # Pinyin, not Jyutping
        return senses, notes

    def original(self, body: str):
        senses, notes = [], []
        for g in body.split("/"):
            g = g.strip()
            if not g:
                continue
            tags = []
            m = REG_RE.search(g)
            if m:
                tags.append(REG_TAG[m.group(1).lower()])
                g = g[:m.start()].strip()
            chunks: list[tuple[str | None, str]] = []
            marks = list(POS_RE.finditer(g))
            if marks:
                if marks[0].start() > 0:
                    chunks.append((None, g[:marks[0].start()]))
                for i, mk in enumerate(marks):
                    end = marks[i + 1].start() if i + 1 < len(marks) else len(g)
                    chunks.append((POS_TAG[mk.group(1).lower()], g[mk.end():end]))
            else:
                m2 = POS_LEAD_RE.match(g)
                chunks.append((POS_TAG[m2.group(1).lower()], g[m2.end():]) if m2 else (None, g))
            for pos, text in chunks:
                text = text.strip().rstrip(";,").strip()
                for part in self._numbered(text):
                    s, n = parse_gloss(part, "cccanto", "tr")
                    for x in s:
                        for t in ([pos] if pos else []) + tags:
                            if t not in x["tags"]:
                                x["tags"].append(t)
                    senses += s
                    notes += [x for x in n if x["kind"] not in ("also_pr", "taiwan_pr")]
        return senses, notes


def _norm_sense(t: str) -> str:
    return " ".join(en_tokens(t)) or t.strip().lower()


def _dedupe(senses: list[dict]) -> list[dict]:
    out, seen = [], set()
    for s in senses:
        k = _norm_sense(s["t"])
        if k in seen:
            continue
        seen.add(k)
        out.append(s)
    return out


# ------------------------------------------------------------------------------------------

def build_cantonese(man: Dataset, hsk_rows: list[dict], cfg: Config):
    ds = Dataset("cantonese")
    stats: Counter = Counter()
    kstats: Counter = Counter()
    rstats: Counter = Counter()
    C = [e for e in man.entries if not e["unihan"]]
    c_by_trad: dict[str, list[dict]] = defaultdict(list)
    c_by_simp: dict[str, list[dict]] = defaultdict(list)
    for c in C:
        c_by_trad[c["trad"]].append(c)
        c_by_simp[c["simp"]].append(c)
    keep = (lambda t, s: s in cfg.fixture_words or t in cfg.fixture_words) if cfg.fixture else (lambda t, s: True)

    # --- R: readings file, joined on (traditional, normalised Pinyin)
    R: dict[tuple[str, str], list[str]] = defaultdict(list)
    r_trads: dict[str, list[str]] = defaultdict(list)
    for trad, simp, pin, jys, _body, _ad in read_canto(cfg.refs / "cccedict-canto-readings-150923.txt", rstats):
        if not keep(trad, simp):
            continue
        if pin != respace_numbered(pin):
            rstats["pinyin_respaced"] += 1
        key = (trad, norm_pinyin_key(pin))
        for j in jys[:1]:
            if j not in R[key]:
                R[key].append(j)
            if j not in r_trads[trad]:
                r_trads[trad].append(j)

    # --- K: CC-Canto, keyed on (traditional, Jyutping); a headword is a (traditional, simplified) pair
    c_by_hw: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for c in C:
        c_by_hw[(c["trad"], c["simp"])].append(c)
    hw_by_trad: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for hw in c_by_hw:
        hw_by_trad[hw[0]].append(hw)
    parser = CantoParser(man, kstats)
    K: dict[tuple[str, str], dict[str, dict]] = defaultdict(dict)       # headword -> jyut -> record
    k_order: list[tuple[str, str]] = []
    for trad, simp, pin, jys, body, adapted in read_canto(cfg.refs / "cccanto-webdist.txt", kstats):
        if not keep(trad, simp):
            continue
        if not jys or not body:
            kstats["lines_without_reading_or_gloss"] += 1
            continue
        if trad not in c_by_trad and trad in c_by_simp:
            trads = sorted({c["trad"] for c in c_by_simp[trad]})
            if len(trads) == 1 and trads[0] != trad:
                kstats["simplified_in_traditional_field_repaired"] += 1
                trad = trads[0]
        if pin.strip() and pin != respace_numbered(pin):
            kstats["pinyin_respaced"] += 1
        hws = hw_by_trad.get(trad)
        if not hws:
            hw = (trad, simp)
        elif (trad, simp) in c_by_hw:
            hw = (trad, simp)
        else:
            hw = max(hws, key=lambda h: (len(c_by_hw[h]), h[1] != trad))
        if hw not in K:
            k_order.append(hw)
        rec = K[hw].setdefault(jys[0], {"simp": hw[1], "pin": pin, "orig": [], "adapted": [], "notes": [], "alts": []})
        s, n = (parser.adapted if adapted else parser.original)(body)
        rec["adapted" if adapted else "orig"] += s
        rec["notes"] += n
        for alt in jys[1:]:
            if alt not in rec["alts"]:
                rec["alts"].append(alt)
        kstats["adapted_lines" if adapted else "original_lines"] += 1

    # --- headword assembly (PIPE-053)
    m2c_all: dict[int, list[int]] = defaultdict(list)       # Mandarin entry id -> carrying Cantonese ids

    def c_order(lst):
        return sorted(lst, key=lambda c: (c["freq_rank"], c["proper"], -len(c["senses"]), c["id"]))

    def carried_senses(clist):
        senses, groups = [], []
        for c in clist:
            if not c["senses"]:
                continue
            first = len(senses)
            for s in c["senses"]:
                s2 = copy.deepcopy(s)
                s2["_cid"] = c["id"]
                senses.append(s2)
            groups.append({"d": c["display"], "c": c["canonical"], "first": first, "count": len(c["senses"])})
        return senses, groups

    def make_entry(trad, simp, jy, rsrc, krec, clist):
        clist = c_order(clist)
        orig = _dedupe(krec["orig"]) if krec else []
        seen = {_norm_sense(s["t"]) for s in orig}
        adapted = [s for s in _dedupe(krec["adapted"]) if _norm_sense(s["t"]) not in seen] if krec else []
        return {"trad": trad, "simp": simp, "jy": jy, "rsrc": rsrc, "orig": orig, "adapted": adapted,
                "knotes": krec["notes"] if krec else [], "alts": krec["alts"] if krec else [], "clist": clist,
                "kpin": krec["pin"] if krec else ""}

    pending: list[dict] = []
    for hw in list(c_by_hw) + [h for h in k_order if h not in c_by_hw]:
        trad = hw[0]
        CT = c_by_hw.get(hw, [])
        KT = K.get(hw, {})
        simp_default = hw[1]
        single = len(trad) == 1
        r_all = r_trads.get(trad, [])
        polyphone = single and CT and (len(CT) > 1 or len(KT) > 1 or len(r_all) > 1)
        made: list[dict] = []
        if not CT:                                             # CC-Canto only
            for jy, rec in KT.items():
                made.append(make_entry(trad, rec["simp"], jy, RSRC["cccanto"], rec, []))
            stats["cccanto_only_entries"] += len(made)
        elif polyphone:
            stats["single_character_polyphones"] += 1
            if KT:
                order = sorted(KT, key=lambda j: (j not in r_all, r_all.index(j) if j in r_all else 0,
                                                  -len(KT[j]["orig"]) - len(KT[j]["adapted"]), j))
                for jy in order:
                    made.append(make_entry(trad, simp_default, jy, RSRC["cccanto"], KT[jy], CT))
            elif r_all:
                stats["polyphones_not_covered_by_cccanto"] += 1
                for jy in r_all:
                    made.append(make_entry(trad, simp_default, jy, RSRC["cccedict_canto"], None, CT))
        else:
            by_j: dict[str, list[dict]] = {}
            unattached = []
            for c in CT:
                js = R.get((trad, c["canonical"]), [])
                if js:
                    stats["r_matches"] += 1
                elif len(r_all) == 1 and not KT:
                    js = r_all                                 # one Jyutping for the headword under another Pinyin
                    stats["r_matches_by_headword_only"] += 1
                if js:
                    pass
                else:
                    unattached.append(c)
                for j in js:
                    by_j.setdefault(j, []).append(c)
            if len(CT) > 1:
                stats["multi_entry_headwords"] += 1
            for jy in list(by_j) + [j for j in KT if j not in by_j]:
                clist = list(by_j.get(jy, []))
                if not clist:
                    clist = list(CT)                           # other CC-Canto reading: same Mandarin block
                else:
                    clist += [c for c in unattached if c not in clist]
                if len(by_j.get(jy, [])) > 1:
                    stats["same_jyutping_merges"] += 1
                rec = KT.get(jy)
                made.append(make_entry(trad, clist[0]["simp"], jy,
                                       RSRC["cccanto"] if rec else RSRC["cccedict_canto"], rec, clist))
        if CT and not made:                                    # no Jyutping anywhere (Q-115)
            groups: dict[str, list[dict]] = defaultdict(list)
            for c in CT:
                groups[c["simp"]].append(c)
            for simp, clist in groups.items():
                made.append(make_entry(trad, simp, "", RSRC["none"], None, clist))
                stats["none_entries"] += 1
            stats["headwords_without_jyutping"] += 1
        # monophone: suppress adapted senses that duplicate current CC-CEDICT senses (ENT-114)
        if len(made) == 1 and made[0]["adapted"] and CT:
            cur = {_norm_sense(s["t"]) for c in CT for s in c["senses"]}
            before = len(made[0]["adapted"])
            made[0]["adapted"] = [s for s in made[0]["adapted"] if _norm_sense(s["t"]) not in cur]
            stats["adapted_senses_suppressed"] += before - len(made[0]["adapted"])
        pending += made

    k_trads = {h[0] for h in K}
    stats["readings_only_headwords_without_entry"] = sum(
        1 for t in r_trads if t not in c_by_trad and t not in k_trads)
    stats["r_headwords_not_in_cedict"] = sum(1 for t in r_trads if t not in c_by_trad)

    # --- materialise entries
    for p in pending:
        canto = p["orig"] + p["adapted"]
        msenses, groups = carried_senses(p["clist"])
        patch = bool(p["clist"]) and all(c["rsrc"] == RSRC["hsk_patch"] for c in p["clist"])
        if canto and msenses:
            have = {_norm_sense(s["t"]) for s in canto}
            dup = sum(1 for s in msenses if _norm_sense(s["t"]) in have)
            if dup >= 0.9 * len(msenses):                      # ENT-115
                msenses, groups = [], []
                stats["mandarin_blocks_dropped"] += 1
        senses = canto + msenses
        blocks = []
        if p["orig"]:
            blocks.append({"kind": "cantonese", "label": BLOCK_LABEL["cantonese"], "first": 0,
                           "count": len(p["orig"]), "collapsed": False})
        if p["adapted"]:
            blocks.append({"kind": "cantonese_adapted", "label": BLOCK_LABEL["cantonese_adapted"],
                           "first": len(p["orig"]), "count": len(p["adapted"]), "collapsed": False})
        if msenses:
            kind = "hsk_patch" if patch else "mandarin"
            for g in groups:
                g["first"] += len(canto)
            blocks.append({"kind": kind, "label": BLOCK_LABEL[kind], "first": len(canto), "count": len(msenses),
                           "collapsed": bool(canto), "readings": groups})
        notes = list(p["knotes"])
        redirect = None
        for c in p["clist"]:
            for n in c["notes"]:
                if n["kind"] in ("also_pr", "taiwan_pr"):
                    continue                                   # Pinyin readings are not Jyutping keys
                n2 = copy.deepcopy(n)
                sig = (n2["kind"], tuple((r["tr"], r["si"]) for r in n2["refs"]))
                if sig not in {(x["kind"], tuple((r["tr"], r["si"]) for r in x["refs"])) for x in notes}:
                    notes.append(n2)
            if c["redirect"] and not senses and len(p["clist"]) == 1:
                redirect = copy.deepcopy(c["redirect"])
        for alt in p["alts"]:
            notes.append({"kind": "also_pr", "label": "also_pr", "refs": [], "reading": alt, "extra": None,
                          "_readings": [alt]})
        if p["jy"]:
            display, canonical, syl, tones, full = parse_jyutping(p["jy"])
        else:
            display = canonical = ""
            syl, tones, full = (), (), False
        if p["clist"]:
            proper = all(c["proper"] for c in p["clist"])
        else:
            proper = bool(re.match(r"[A-Z]", p["kpin"]))
        e = ds.add(trad=p["trad"], simp=p["simp"], display=display, canonical=canonical, cased=canonical,
                   syllables=syl, tones=tones, proper=proper, full_parse=full, rsrc=p["rsrc"],
                   senses=senses, notes=notes, blocks=blocks, redirect=redirect, canto=bool(canto),
                   carries=[c["id"] for c in p["clist"]],
                   segmentable=p["trad"] not in PRONOUN_GE)
        for c in p["clist"]:
            m2c_all[c["id"]].append(e["id"])
    ds.n_dictionary = len(ds.entries)
    stats["pronoun_ge_headwords"] = sum(1 for e in ds.entries if not e["segmentable"])

    ds.report["unihan"] = add_unihan_entries(ds, cfg, "kCantonese")

    # --- Cantonese frequency table + LM corpora (DATA-189, PIPE-077)
    from .yue_corpus import build_frequency
    corpus = build_frequency(ds, cfg)
    assign_freq_first = True

    # primary Cantonese entry per Mandarin entry: most frequent, then lowest id (DATA-182)
    lm_dummy_rank = {w: i for i, (w, _c) in enumerate(sorted(ds.freq.items(), key=lambda kv: (-kv[1], kv[0])))}
    m2c: dict[int, int] = {}
    multi_primary = 0
    for cid, ids in m2c_all.items():
        m2c[cid] = min(ids, key=lambda i: (lm_dummy_rank.get(ds.entries[i]["trad"], 10 ** 9), i))
        if len(ids) > 1:
            multi_primary += 1
    stats["mandarin_entries_with_several_cantonese_entries"] = multi_primary
    missing = [c["id"] for c in C if c["id"] not in m2c]
    if missing:
        raise RuntimeError(f"{len(missing)} CC-CEDICT entries are not carried by the Cantonese dataset")

    # --- references
    heads: dict[str, list[int]] = defaultdict(list)
    for e in ds.entries:
        heads[fold(e["trad"])].append(e["id"])
        if e["simp"] != e["trad"]:
            heads[fold(e["simp"])].append(e["id"])
    mres = Resolver(man.entries)

    def fix_segs(segs: list[dict], canto_src: bool, self_id: int) -> list[dict]:
        out: list[dict] = []
        for seg in segs:
            if seg["k"] != "ref":
                out.append(seg)
                continue
            if "id" in seg and not canto_src:                  # carried CC-CEDICT reference
                mid = seg.pop("id")
                tgt = m2c.get(mid)
                if tgt is not None and tgt != self_id:
                    seg["id"] = tgt
                out.append(seg)
                continue
            if "c" in seg:                                     # reference with Pinyin (Pleco style)
                mid = mres.resolve(seg)
                tgt = m2c.get(mid) if mid is not None else None
                if tgt is not None and tgt != self_id:
                    seg["id"] = tgt
                    kstats["pleco_refs_resolved"] += 1
                out.append(seg)
                continue
            ids = sorted(set(heads.get(seg["tr"], ())) | set(heads.get(seg["si"], ())))
            if canto_src and not ids:                          # bare run that is not a headword: plain text
                kstats["bare_runs_kept_as_text"] += 1
                if out and out[-1]["k"] == "t":
                    out[-1] = {"k": "t", "t": out[-1]["t"] + seg["tr"]}
                else:
                    out.append({"k": "t", "t": seg["tr"]})
                continue
            if canto_src:
                kstats["bare_runs_linked"] += 1
            ids = [i for i in ids if i != self_id]
            if len(ids) == 1:
                seg["id"] = ids[0]
            out.append(seg)
        merged: list[dict] = []
        for seg in out:
            if seg["k"] == "t" and merged and merged[-1]["k"] == "t":
                merged[-1] = {"k": "t", "t": merged[-1]["t"] + seg["t"]}
            else:
                merged.append(seg)
        return merged

    for e in ds.entries:
        for s in e["senses"]:
            canto_src = s["src"] in ("cccanto", "cccanto_adapted")
            if s["src"] == "unihan":
                for seg in s["seg"]:
                    seg.pop("id", None) if seg["k"] == "ref" else None
            s["seg"] = fix_segs(s["seg"], canto_src, e["id"])
            s["t"] = plain_text(s["seg"], "tr")
            if canto_src:
                s["tok"] = s["tok"] if s["tags"] and ("surname" in s["tags"] or "measure_word" in s["tags"]) \
                    else en_tokens(" ".join(x["t"] for x in s["seg"] if x["k"] == "t"))
        for n in e["notes"]:
            if n.get("_seg"):
                n["_seg"] = fix_segs(n["_seg"], False, e["id"])
            fixed = fix_segs([dict(r, k="ref") for r in n["refs"]], False, e["id"])
            n["refs"] = [{k: v for k, v in r.items() if k != "k"} for r in fixed if r["k"] == "ref"]
        if e["redirect"]:
            e["redirect"]["seg"] = fix_segs(e["redirect"]["seg"], False, e["id"])
            e["redirect"]["text"] = plain_text(e["redirect"]["seg"], "tr")

    # --- keys, redirects, alternative readings, merge groups
    ds.radical_forms = man.radical_forms
    assign_natural_keys(ds)
    finish_redirects(ds)
    count_refs(ds)
    build_alt_readings(ds, lambda raw: parse_jyutping(" ".join(norm_jyutping(raw, Counter())[:1])))
    build_merge_groups(ds)

    # --- syllable tables
    ds.syllables = sorted(jyutping_inventory())
    ds.aliases = load_aliases("jyutping_aliases.tsv")
    ds.abbrevs = jyutping_abbrevs(ds.syllables)

    # --- LM + frequency ranks
    from .yue_corpus import cantonese_lm
    clm = cantonese_lm(ds, cfg, corpus)
    assign_frequency(ds, "trad", dict(zip(clm.words, clm.logp)), clm.observed)
    lmmod.build_abbr(ds, clm, cfg)

    # --- English postings (DATA-076): Cantonese blocks per entry (flag 1); Mandarin block once per
    #     CC-CEDICT entry on its primary Cantonese entry; Unihan flag 2
    post: dict[str, set] = defaultdict(set)
    for e in ds.entries:
        for i, s in enumerate(e["senses"]):
            if s["src"] in ("cccanto", "cccanto_adapted"):
                fl = 1
            elif s["src"] == "unihan":
                fl = 2
            else:
                if m2c.get(s.get("_cid")) != e["id"]:
                    continue
                fl = 0
            for tok in set(s["tok"]):
                post[tok].add((e["id"], i, fl))
    ds.en_primary = post

    # --- HSK (DATA-187, PIPE-056)
    rows = []
    decisions = 0
    for r in hsk_rows:
        r2 = {k: v for k, v in r.items() if not k.startswith("_")}
        mentry = r["_entry"]
        e = ds.entries[m2c[mentry["id"]]]
        if len(m2c_all[mentry["id"]]) > 1:
            decisions += 1
        also = []
        for a in r["_also"]:
            t = m2c[a["id"]]
            if t != e["id"] and t not in also:
                also.append(t)
        r2["_entry"], r2["_also"] = e, [ds.entries[i] for i in also]
        r2["entry_id"], r2["also_entry_ids"] = e["id"], also
        r2["trad"] = e["trad"]
        if e["rsrc"] == RSRC["none"]:
            r2["_nojy"] = 1
            r2["sort_key"] = hskmod.entry_sort_key(mentry)
        else:
            r2["_nojy"] = 0
            r2["sort_key"] = hskmod.entry_sort_key(e)
        rows.append(r2)
    hskmod.apply_levels(rows, lambda r: [r["_entry"]] + r["_also"])
    hskmod.order_rows(rows, lambda r: (r["level"], r["_nojy"], r["sort_key"], r["simp"], r["row_id"]))
    ds.hsk_rows = rows
    via = 0
    for me in man.entries:
        if me["hsk_via"]:
            for cid in m2c_all.get(me["id"], ()):
                ce = ds.entries[cid]
                if not ce["hsk"] and not ce["hsk_via"]:
                    ce["hsk_via"] = copy.deepcopy(me["hsk_via"])
                    via += 1
    pin_of = {}
    for e in ds.entries:
        if e["rsrc"] == RSRC["none"] and e.get("carries"):
            pin_of[e["id"]] = hskmod.entry_sort_key(man.entries[e["carries"][0]])
    ds.report["hsk"] = {"rows": len(rows), "per_level": dict(sorted(Counter(r["level"] for r in rows).items())),
                        "primary_entry_decisions": decisions, "rows_without_jyutping": sum(r["_nojy"] for r in rows),
                        "entries_with_levels_via_variant": via}
    build_hsk_chars(ds, cfg, sort_key_of=lambda e: pin_of.get(e["id"]) or hskmod.entry_sort_key(e))

    # --- report
    nk, tg = Counter(), Counter()
    for e in ds.entries:
        for n in e["notes"]:
            nk[n["kind"]] += 1
        for s in e["senses"]:
            for t in s["tags"]:
                tg[t] += 1
    ds.report.update({
        "join": dict(sorted(stats.items())), "cccanto_hygiene": dict(sorted(kstats.items())),
        "readings_file_hygiene": dict(sorted(rstats.items())),
        "notes": {"by_kind": dict(sorted(nk.items()))}, "tags": dict(sorted(tg.items())),
        "blocks": dict(sorted(Counter(b["kind"] for e in ds.entries for b in e["blocks"]).items())),
        "reading_sources": dict(sorted(Counter(e["rsrc"] for e in ds.entries).items())),
        "corpus": corpus["report"]})
    ds.lm_source = corpus["lm_source"]
    ds.corpus_sources = corpus["sources"]
    return ds, clm
