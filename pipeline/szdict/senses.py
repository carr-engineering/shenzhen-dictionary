"""Note-convention parser and sense segmentation (PIPE-025, PIPE-027, PIPE-079, DATA-050…DATA-062)."""
from __future__ import annotations

import json
import re
from pathlib import Path

from .norm import HANZI_CLASS, en_tokens, fold, has_hanzi
from .roman import parse_pinyin, pinyin_inventory, respace_numbered

CONTRACT = Path(__file__).resolve().parents[2] / "contract"

_H = HANZI_CLASS
_W = f"[A-Za-z0-9{_H}]"
_MIX = f"(?:{_W}|(?<={_W})[·・‧･•，](?={_W}))+"
_RUN = f"(?:[{_H}]|(?<=[{_H}])[·・‧･•](?=[{_H}]))+"
SEG_RE = re.compile(
    f"(?P<a>{_MIX})\\|(?P<b>{_MIX})(?:\\[(?P<p>[^\\]]*)\\])?"
    f"|(?P<c>{_MIX})\\[(?P<q>[^\\]]*)\\]"
    f"|(?P<r>{_RUN})"
    f"|\\[(?P<x>[^\\]]*)\\]"
)

_CUES = [
    (re.compile(r"Tai-?lo", re.I), "tailo"),
    (re.compile(r"Jyutping", re.I), "jyutping"),
    (re.compile(r"Cantonese|Cant\."), "jyutping"),
    (re.compile(r"Hokkien|Taiwanese|Minnan|Min Nan"), "tailo"),
    (re.compile(r"Teochew"), "teochew"),
    (re.compile(r"Wade-Giles"), "wade_giles"),
    (re.compile(r"Peh-oe-ji|POJ"), "poj"),
    (re.compile(r"Mandarin|Taiwan pr\.|Beijing pr\.|also pr\.|pronounced|the reading|pr\. "), "pinyin"),
]

_PINYINISH = re.compile(r"(?:[A-Za-zü:]+[1-5]|[A-Z]{1,3}|[,·]|\d+)(?:\s*(?:[A-Za-zü:]+[1-5]|[A-Z]{1,3}|[,·]|\d+))*")


def looks_pinyin(reading: str) -> bool:
    r = reading.strip()
    if not r or not _PINYINISH.fullmatch(r):
        return False
    inv = pinyin_inventory()
    toks = respace_numbered(r).split()
    ok = sum(1 for t in toks if t[-1:].isdigit() and t[:-1].lower().replace("u:", "ü") in inv)
    return ok > 0 and ok >= len([t for t in toks if t[-1:].isdigit()])


def classify_reading(text: str, pos: int, reading: str) -> str:
    """DATA-053 / PIPE-025: nearest preceding cue within ~96 characters wins."""
    window = text[max(0, pos - 96):pos]
    best = None
    for rx, system in _CUES:
        for m in rx.finditer(window):
            if best is None or m.end() > best[0]:
                best = (m.end(), system)
    if best is not None:
        if best[1] == "pinyin" and not looks_pinyin(reading):
            return "unknown"
        return best[1]
    return "pinyin" if looks_pinyin(reading) else "unknown"


def make_ref(tr: str, si: str, reading: str | None, rom: str = "pinyin") -> dict:
    ref = {"k": "ref", "tr": fold(tr), "si": fold(si)}
    if reading is not None:
        reading = reading.strip()
        if rom == "pinyin":
            d, c, _s, _t, _p, _f, cased = parse_pinyin(respace_numbered(reading))
            ref["d"], ref["c"], ref["_cased"] = d, c, cased
        else:
            ref["d"] = reading
    ref["rom"] = rom
    return ref


def parse_segments(text: str, *, refs: bool = True) -> list[dict]:
    """Split sense text into TEXT / READING / REF segments (DATA-050…DATA-053)."""
    segs: list[dict] = []
    pos = 0

    def push_text(s: str) -> None:
        if not s:
            return
        if segs and segs[-1]["k"] == "t":
            segs[-1]["t"] += s
        else:
            segs.append({"k": "t", "t": s})

    for m in SEG_RE.finditer(text):
        push_text(text[pos:m.start()])
        pos = m.end()
        if m.group("a") is not None:
            a, b, p = m.group("a"), m.group("b"), m.group("p")
            if refs and has_hanzi(a) and has_hanzi(b):
                rom = "pinyin"
                if p is not None and not looks_pinyin(p):
                    rom = classify_reading(text, m.start(), p)
                segs.append(make_ref(a, b, p, rom))
            else:
                push_text(m.group(0))
        elif m.group("c") is not None:
            c, q = m.group("c"), m.group("q")
            if refs and has_hanzi(c):
                rom = "pinyin" if looks_pinyin(q) else classify_reading(text, m.start(), q)
                segs.append(make_ref(c, c, q, rom))
            else:
                push_text(c)
                rom = classify_reading(text, m.start("q"), q)
                segs.append(make_reading(q, rom))
        elif m.group("r") is not None:
            r = m.group("r")
            if refs:
                segs.append(make_ref(r, r, None))
            else:
                push_text(r)
        else:
            x = m.group("x")
            if x.strip() == "":
                push_text(m.group(0))
            else:
                segs.append(make_reading(x, classify_reading(text, m.start(), x)))
    push_text(text[pos:])
    return segs


def make_reading(reading: str, rom: str) -> dict:
    reading = reading.strip()
    if rom == "pinyin":
        d = parse_pinyin(respace_numbered(reading))[0]
    else:
        d = reading
    return {"k": "r", "d": d, "rom": rom}


def plain_text(segs: list[dict], script: str) -> str:
    """Display text of a segment list: refs as the Hanzi of `script` ('si'/'tr'), readings bracketed."""
    out = []
    for s in segs:
        if s["k"] == "t":
            out.append(s["t"])
        elif s["k"] == "r":
            out.append(f"[{s['d']}]")
        else:
            out.append(s[script])
    return "".join(out)


# ---------------------------------------------------------------------------------------------

class Labels:
    def __init__(self) -> None:
        data = json.loads((CONTRACT / "labels.json").read_text("utf-8"))
        self.rows = data["labels"]
        self.hsk_pos = data["hsk_pos"]
        self.keys = {r["key"] for r in self.rows}
        self.tag_by_source: dict[str, str] = {}
        for r in self.rows:
            for s in r.get("source", []):
                self.tag_by_source[s.lower()] = r["key"]


_LABELS: Labels | None = None


def labels() -> Labels:
    global _LABELS
    if _LABELS is None:
        _LABELS = Labels()
    return _LABELS


NOTE_PREFIXES = [
    ("see_also", re.compile(r"see also ", re.I)),
    ("see", re.compile(r"see ", re.I)),
    ("old_variant_of", re.compile(r"(?:old|ancient|archaic) variant of ", re.I)),
    ("erhua_variant_of", re.compile(r"erhua (?:variant|form) of ", re.I)),
    ("japanese_variant_of", re.compile(r"Japanese variant of ", re.I)),
    ("contracted_form", re.compile(r"contracted (?:variant|form) of ", re.I)),
    ("variant_of", re.compile(r"variant of ", re.I)),
    ("classifier", re.compile(r"CL:\s*")),
    ("abbr_for", re.compile(r"abbr\. (?:for|of) ", re.I)),
    ("also_written", re.compile(r"also written (?:as )?", re.I)),
    ("used_in", re.compile(r"used in ", re.I)),
]
VARIANT_KINDS = {"variant_of": "variant", "old_variant_of": "old_variant", "erhua_variant_of": "erhua"}
_SEP_ONLY = re.compile(r"[\s,;、，]*(?:(?:or|and|etc)\b[\s,;.]*)?")
_PR_RE = re.compile(
    r"(?P<kind>also|Taiwan) pr\.\s*(?P<rd>(?:\[[^\]]+\](?:\s*(?:,|/|or|and)\s*)*)+)(?P<rest>.*)", re.I | re.S)
_TRAIL_PR_RE = re.compile(
    r"\s*\((?P<kind>also|Taiwan) pr\.\s*(?P<rd>(?:\[[^\]]+\](?:\s*(?:,|/|or|and)\s*)*)+)\)\s*$", re.I)
_LEAD_TAG_RE = re.compile(r"\(([^()]{1,40})\)\s*")
_EVR_RE = re.compile(r"\((?:also |old |erhua )?(?:variant|form) of [^()]*\)\s*$", re.I)
_BRACKET_RE = re.compile(r"\[([^\]]+)\]")


def _pr_note(kind_word: str, readings_text: str, extra: str, label_extra: str = "") -> dict:
    kind = "taiwan_pr" if kind_word.lower() == "taiwan" else "also_pr"
    raws = [respace_numbered(r.strip()) for r in _BRACKET_RE.findall(readings_text)]
    disp = [parse_pinyin(r)[0] if looks_pinyin(r) else r for r in raws]
    extra = extra.strip().lstrip(",;").strip() or None
    return {"kind": kind, "label": kind, "refs": [], "reading": ", ".join(disp), "extra": extra,
            "_readings": raws}


def parse_gloss(gloss: str, src: str, script: str, *, flavour: str = "cedict"):
    """One CC-CEDICT gloss -> (senses, notes). A gloss yields a note, or one sense plus
    possibly trailing alt-reading notes."""
    lab = labels()
    g = gloss.strip()
    notes: list[dict] = []
    if not g:
        return [], []

    # whole-gloss alternative readings
    m = _PR_RE.fullmatch(g)
    if m:
        return [], [_pr_note(m.group("kind"), m.group("rd"), m.group("rest"))]

    # trailing "(Taiwan pr. [..])" / "(also pr. [..])"
    while True:
        m = _TRAIL_PR_RE.search(g)
        if not m or m.start() == 0:
            break
        notes.append(_pr_note(m.group("kind"), m.group("rd"), ""))
        g = g[:m.start()].rstrip()

    # whole-gloss structural notes
    for kind, rx in NOTE_PREFIXES:
        m = rx.match(g)
        if not m:
            continue
        segs = parse_segments(g[m.end():])
        refs = [s for s in segs if s["k"] == "ref"]
        rest_ok = all(
            s["k"] == "ref" or (s["k"] == "t" and _SEP_ONLY.fullmatch(s["t"])) for s in segs)
        if refs and rest_ok:
            note = {"kind": kind, "label": kind,
                    "refs": [{k: v for k, v in r.items() if k != "k"} for r in refs],
                    "reading": None, "extra": None, "_text": g, "_seg": parse_segments(g)}
            return [], [note] + notes
        break

    tags: list[str] = []
    full_tokens = None
    # leading allow-listed parenthesised tags
    while True:
        m = _LEAD_TAG_RE.match(g)
        if not m:
            break
        inner = m.group(1).strip()
        parts = [p.strip().lower() for p in re.split(r",\s*|\s*/\s*", inner)]
        if all(p in lab.tag_by_source for p in parts) and len(g) > m.end():
            for p in parts:
                t = lab.tag_by_source[p]
                if t not in tags:
                    tags.append(t)
            g = g[m.end():]
        else:
            break
    # structural sense tags
    m = re.match(r"surname (?=\S)", g)
    if m and len(g) < 60:
        tags.append("surname")
        full_tokens = en_tokens(g)
        g = g[m.end():]
    elif re.match(r"classifier for ", g):
        tags.append("measure_word")
        full_tokens = en_tokens(g)
        g = g[len("classifier "):]
    elif re.match(r"Kangxi radical \d+", g):
        tags.append("radical")

    segs = parse_segments(g)
    text = plain_text(segs, script)
    if not text.strip():
        return [], notes
    sense = {"t": text, "src": src, "tags": tags, "seg": segs,
             "tok": full_tokens if full_tokens is not None else en_tokens(_tok_text(segs))}
    if _EVR_RE.search(g) and any(s["k"] == "ref" for s in segs):
        sense["evr"] = True
    return [sense], notes


def _tok_text(segs: list[dict]) -> str:
    return " ".join(s["t"] for s in segs if s["k"] == "t")


def summarise(senses: list[dict], limit: int = 240) -> str:
    out = ""
    for s in senses:
        t = s["t"]
        cand = t if not out else out + "; " + t
        if len(cand) > limit:
            if not out:
                out = t[: limit - 1].rstrip() + "…"
            break
        out = cand
    return out
