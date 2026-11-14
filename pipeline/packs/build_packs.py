# /// script
# requires-python = ">=3.10"
# dependencies = ["opencc-python-reimplemented>=0.1.7", "jieba>=0.42.1"]
# ///
"""Build the companion asset packs (contract/SCHEMA.md "Packs").

    uv run pipeline/packs/build_packs.py [--only sentences|decomposition|audio]

Outputs (pipeline/out/packs/, gitignored):
    sentences.db        Mandarin example sentences, keyed by simplified headword
    sentences-yue.db    Cantonese example sentences, keyed by traditional headword
    decomposition.db    IDS decomposition (one sequence per character, SCHEMA note 24),
                        radical/strokes, names, Make Me a Hanzi matches, reverse component
                        index (part_of), written forms of radicals (radical_form)
    audio/              cmn-syl/, cmn-word/, yue-syl/ + catalog.json
    packs-report.json   counts and coverage of the run

Downloads are cached in pipeline/sources/packs/ (gitignored). Output is deterministic for
fixed inputs.
"""
from __future__ import annotations

import argparse
import bz2
import hashlib
import io
import json
import re
import shutil
import sqlite3
import sys
import unicodedata
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from radical_names import COMPONENT_NAMES, RADICAL_NAMES  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
REFS = ROOT / "refs"
SRC = ROOT / "pipeline" / "sources" / "packs"
OUT = ROOT / "pipeline" / "out" / "packs"

OLD_SENTENCES = Path(
    "/Users/marc/Documents/projects/superchinese/dictionary_app/composeApp/src/androidMain/assets/sentences"
)
AUDIO_REPO = Path("/Users/marc/Documents/code/shenzhen-mandarin-audio")

SCHEMA_VERSION = 1
SENT_CAP = 20          # MED-046
YUE_MAX_CHARS = 20     # MED-048 / DATA-188
MAX_WORD_LEN = 12

DOWNLOADS = {
    "ids/IDS.TXT": "https://www.babelstone.co.uk/CJK/IDS.TXT",
    "unihan/Unihan.zip": "https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip",
    "mmah/dictionary.txt": "https://raw.githubusercontent.com/skishore/makemeahanzi/master/dictionary.txt",
    "ucd/EquivalentUnifiedIdeograph.txt": "https://www.unicode.org/Public/UCD/latest/ucd/EquivalentUnifiedIdeograph.txt",
    "ucd/CJKRadicals.txt": "https://www.unicode.org/Public/UCD/latest/ucd/CJKRadicals.txt",
    "tatoeba/yue-eng_links.tsv.bz2": "https://downloads.tatoeba.org/exports/per_language/yue/yue-eng_links.tsv.bz2",
    "tatoeba/eng_sentences.tsv.bz2": "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2",
}


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def source(rel: str) -> Path:
    """Path of a cached download, fetching it on first use."""
    path = SRC / rel
    if not path.exists() or path.stat().st_size == 0:
        path.parent.mkdir(parents=True, exist_ok=True)
        log(f"download {DOWNLOADS[rel]}")
        req = urllib.request.Request(DOWNLOADS[rel], headers={"User-Agent": "packs-build/1.0"})
        tmp = path.with_suffix(path.suffix + ".part")
        with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as fh:
            shutil.copyfileobj(resp, fh)
        tmp.replace(path)
    return path


# --------------------------------------------------------------------------------------
# shared helpers
# --------------------------------------------------------------------------------------

def is_han(ch: str) -> bool:
    o = ord(ch)
    return (
        0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0x20000 <= o <= 0x323AF
        or 0xF900 <= o <= 0xFAFF or 0x2F800 <= o <= 0x2FA1F or o == 0x3007
    )


def has_han(s: str) -> bool:
    return any(is_han(c) for c in s)


CEDICT_LINE = re.compile(r"^(\S+) (\S+) \[([^\]]*)\]")


def read_cedict(path: Path):
    """Yield (trad, simp, reading) from a CC-CEDICT format file (CC-Canto shares it)."""
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#"):
                continue
            m = CEDICT_LINE.match(line)
            if m:
                yield m.group(1), m.group(2), m.group(3), line


def varints(ids) -> bytes:
    out = bytearray()
    for n in ids:
        while n >= 0x80:
            out.append((n & 0x7F) | 0x80)
            n >>= 7
        out.append(n)
    return bytes(out)


def open_db(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    for p in (path, Path(str(path) + "-journal")):
        if p.exists():
            p.unlink()
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("PRAGMA page_size = 4096")
    db.execute("PRAGMA journal_mode = DELETE")
    db.execute("BEGIN")
    return db


def close_db(db: sqlite3.Connection) -> None:
    db.execute("COMMIT")
    db.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    db.execute("ANALYZE")
    db.execute("VACUUM")
    db.close()


def write_meta(db: sqlite3.Connection, meta: dict) -> None:
    # Not part of the reader contract; documents the pack (PIPE-060) without timestamps.
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID")
    db.executemany(
        "INSERT INTO meta VALUES(?,?)",
        sorted((k, v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, separators=(",", ":")))
               for k, v in meta.items()),
    )


def jdump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def update_report(section: str, data: dict) -> None:
    path = OUT / "packs-report.json"
    report = {}
    if path.exists():
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            report = {}
    report[section] = data
    path.write_text(json.dumps(report, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")


# --------------------------------------------------------------------------------------
# sentences
# --------------------------------------------------------------------------------------

SENT_SCHEMA = """
CREATE TABLE sentence(id INTEGER PRIMARY KEY, zh TEXT, zh_alt TEXT, en TEXT);
CREATE TABLE word_sentences(word TEXT PRIMARY KEY, ids BLOB) WITHOUT ROWID;
"""


def index_sentence(text: str, tokens: list[str], lexicon: set[str], max_len: int):
    """Yield (headword, tier) for every lexicon headword occurring in `text`.

    tier 0: the headword is exactly one token of the segmented sentence (standalone, MED-047);
    tier 1: it is a run of two or more whole tokens (the segmenter split a dictionary word);
    tier 2: it lies inside a single longer token.
    An occurrence that cuts through token boundaries (和服 in 我|和|服务员) is not an example of
    the headword and is skipped.
    """
    starts, tok_at = {}, []
    pos = 0
    for ti, tok in enumerate(tokens):
        starts[pos] = ti
        tok_at.extend([ti] * len(tok))
        pos += len(tok)
    ends = {s + len(tokens[ti]): ti for s, ti in starts.items()}
    n = len(text)
    best: dict[str, int] = {}
    for i in range(n):
        if not is_han(text[i]):
            continue
        for j in range(i + 1, min(n, i + max_len) + 1):
            w = text[i:j]
            if w not in lexicon:
                continue
            if i in starts and j in ends:
                tier = 0 if starts[i] == ends[j] else 1
            elif tok_at[i] == tok_at[j - 1]:
                tier = 2
            else:
                continue
            if tier < best.get(w, 9):
                best[w] = tier
    return best.items()


def write_sentence_pack(path: Path, sentences: dict[int, tuple[str, str, str]],
                        postings: dict[str, list[tuple[int, int, int]]], meta: dict) -> dict:
    """sentences: id -> (zh, zh_alt, en); postings: word -> [(tier, length, id)]."""
    index: dict[str, list[int]] = {}
    for word, cands in postings.items():
        seen_text, ids = set(), []
        for _tier, _length, sid in sorted(cands):
            zh = sentences[sid][0]
            if zh in seen_text:
                continue
            seen_text.add(zh)
            ids.append(sid)
            if len(ids) == SENT_CAP:
                break
        index[word] = ids
    used = sorted({sid for ids in index.values() for sid in ids})
    db = open_db(path)
    for stmt in SENT_SCHEMA.strip().split(";\n"):
        if stmt.strip():
            db.execute(stmt)
    db.executemany("INSERT INTO sentence VALUES(?,?,?,?)", [(sid, *sentences[sid]) for sid in used])
    db.executemany("INSERT INTO word_sentences VALUES(?,?)",
                   [(w, varints(index[w])) for w in sorted(index)])
    stats = {
        "sentences_source": len(sentences),
        "sentences_stored": len(used),
        "words": len(index),
        "words_with_standalone_example": sum(
            1 for w, c in postings.items() if any(t == 0 for t, _, _ in c)),
        "words_at_cap": sum(1 for ids in index.values() if len(ids) == SENT_CAP),
    }
    write_meta(db, {**meta, **{k: str(v) for k, v in stats.items()}})
    close_db(db)
    stats["bytes"] = path.stat().st_size
    return stats


def cedict_headwords():
    simp, trad = set(), set()
    for t, s, _r, _l in read_cedict(REFS / "cedict_ts.u8"):
        if has_han(s) and len(s) <= MAX_WORD_LEN:
            simp.add(s)
            trad.add(t)
    return simp, trad


def build_sentences_cmn() -> dict:
    import jieba
    from opencc import OpenCC

    jieba.setLogLevel(60)
    # Traditional text follows the dictionary's own traditional forms so that a headword is
    # found in (and highlighted in, MED-042) its example: OpenCC's Taiwan-standard character
    # set (裡 著 床, as CC-CEDICT writes them), then every segmented word that CC-CEDICT gives
    # exactly one traditional form is written that way (头发 -> 頭髮, 干净 -> 乾淨).
    t2s, s2t = OpenCC("t2s"), OpenCC("s2tw")
    lexicon, _ = cedict_headwords()
    max_len = max(len(w) for w in lexicon)
    trads: dict[str, set[str]] = defaultdict(set)
    for t, s_, _r, _l in read_cedict(REFS / "cedict_ts.u8"):
        trads[s_].add(t)
    uniq_trad = {s_: next(iter(ts)) for s_, ts in trads.items()
                 if len(ts) == 1 and len(s_) >= 2 and len(next(iter(ts))) == len(s_)}

    def to_trad(zh: str, tokens: list[str]) -> str:
        base = s2t.convert(zh)
        if len(base) != len(zh):
            return base
        out, pos = list(base), 0
        for tok in tokens:
            if tok in uniq_trad:
                out[pos:pos + len(tok)] = uniq_trad[tok]
            elif tok == "刮":  # OpenCC's default 颳 is only the wind's 刮 (刮风 is a word)
                out[pos] = "刮"
            pos += len(tok)
        return "".join(out)

    shards = sorted(OLD_SENTENCES.glob("s-*.json"), key=lambda p: int(p.stem.split("-")[1]))
    sentences: dict[int, tuple[str, str, str]] = {}
    postings: dict[str, list] = defaultdict(list)
    sid = mispaired = 0
    for shard in shards:
        for row in json.loads(shard.read_text(encoding="utf-8")):
            sid += 1  # id = position in the source shards, 1-based
            zh = t2s.convert(row[0].strip())
            en = row[2].strip()
            if not zh or not en:
                continue
            if len(en) > 12 * sum(1 for c in zh if is_han(c)) + 10:
                mispaired += 1  # e.g. 好 = "To write a love letter in English is not easy."
                continue
            tokens = jieba.lcut(zh, HMM=False)
            sentences[sid] = (zh, to_trad(zh, tokens), en)
            for word, tier in index_sentence(zh, tokens, lexicon, max_len):
                postings[word].append((tier, len(zh), sid))
    stats = write_sentence_pack(OUT / "sentences.db", sentences, postings, {
        "pack": "sentences", "language": "cmn", "key": "simplified headword",
        "source": "Tatoeba eng-cmn (Helsinki-NLP/Tatoeba-Challenge), CC BY 2.0 FR",
    })
    stats.update(headwords_in_lexicon=len(lexicon), excluded_mispaired=mispaired)
    log(f"sentences.db: {stats}")
    return stats


PRONOUN_GE = re.compile(r"^(我|你|妳|佢|我哋|你哋|妳哋|佢哋|我地|你地|佢地)嘅$")


def segment_min_tokens(text: str, lexicon: set[str], max_len: int) -> list[str]:
    """Dictionary segmentation: fewest tokens, ties broken towards the longer leftmost word."""
    n = len(text)
    best = [(0, 0)] * (n + 1)  # (token count, -first token length) from i to end
    nxt = [n] * (n + 1)
    for i in range(n - 1, -1, -1):
        cand = None
        for j in range(i + 1, min(n, i + max_len) + 1):
            if j == i + 1 or text[i:j] in lexicon:
                key = (best[j][0] + 1, -(j - i))
                if cand is None or key < cand[0]:
                    cand = (key, j)
        best[i], nxt[i] = cand
    out, i = [], 0
    while i < n:
        out.append(text[i:nxt[i]])
        i = nxt[i]
    return out


def build_sentences_yue() -> dict:
    from opencc import OpenCC

    t2s = OpenCC("t2s")
    lexicon = set()
    for t, _s, _r, _l in read_cedict(REFS / "cccanto-webdist.txt"):
        if has_han(t) and len(t) <= MAX_WORD_LEN:
            lexicon.add(t)
    lexicon |= cedict_headwords()[1]
    seg_lexicon = {w for w in lexicon if not PRONOUN_GE.match(w)}  # DATA-189
    max_len = max(len(w) for w in lexicon)

    yue: dict[int, str] = {}
    total = long_skipped = 0
    with open(REFS / "yue_sentences.tsv", encoding="utf-8") as fh:
        for line in fh:
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 3 or not cols[0].isdigit():
                continue
            total += 1
            text = cols[2].strip()
            if len(text) > YUE_MAX_CHARS:
                long_skipped += 1
                continue
            yue[int(cols[0])] = text

    links: dict[int, list[int]] = defaultdict(list)
    with bz2.open(source("tatoeba/yue-eng_links.tsv.bz2"), "rt", encoding="utf-8") as fh:
        for line in fh:
            a, _, b = line.strip().partition("\t")
            if a.isdigit() and b.isdigit() and int(a) in yue:
                links[int(a)].append(int(b))
    wanted = {e for ids in links.values() for e in ids}
    eng: dict[int, str] = {}
    with bz2.open(source("tatoeba/eng_sentences.tsv.bz2"), "rt", encoding="utf-8") as fh:
        for line in fh:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 3 and cols[0].isdigit() and int(cols[0]) in wanted:
                eng[int(cols[0])] = cols[2].strip()

    sentences: dict[int, tuple[str, str, str]] = {}
    postings: dict[str, list] = defaultdict(list)
    untranslated = 0
    for sid in sorted(yue):
        ens = [eng[e] for e in sorted(links.get(sid, ())) if eng.get(e)]
        if not ens:
            untranslated += 1
            continue
        zh = yue[sid]
        sentences[sid] = (zh, t2s.convert(zh), ens[0])  # lowest English sentence id
        tokens = segment_min_tokens(zh, seg_lexicon, max_len)
        for word, tier in index_sentence(zh, tokens, lexicon, max_len):
            postings[word].append((tier, len(zh), sid))
    stats = write_sentence_pack(OUT / "sentences-yue.db", sentences, postings, {
        "pack": "sentences-yue", "language": "yue", "key": "traditional headword",
        "source": "Tatoeba yue sentences + eng translations, CC BY 2.0 FR; id = Tatoeba sentence id",
    })
    stats.update(source_sentences=total, excluded_long=long_skipped,
                 excluded_untranslated=untranslated, headwords_in_lexicon=len(lexicon))
    log(f"sentences-yue.db: {stats}")
    return stats


# --------------------------------------------------------------------------------------
# decomposition
# --------------------------------------------------------------------------------------

ARITY = {c: 2 for c in "⿰⿱⿴⿵⿶⿷⿸⿹⿺⿻⿼⿽㇯"}
ARITY.update({"⿲": 3, "⿳": 3, "⿾": 1, "⿿": 1})
VARIATION = "〾"
UNKNOWN = "？"
TOKEN = re.compile(r"\{\d+\}|.", re.S)


# Components IDS.TXT can only spell as a nested sequence that are, as written, a form of an encoded
# character: the hand on the left of 我 (⿱㇒扌, 手 with its hook) is 手 (Pleco, Wenlin: 手 + 戈).
NESTED_ALIASES = {"⿱㇒扌": "手"}
# Glyphs that stand for two Kangxi radicals; Unihan gives each only one (月 74 moon, 阝 170 mound).
SHARED_FORMS = {"月": "肉", "阝": "邑"}


class IdsError(ValueError):
    pass


def parse_ids(seq: str):
    """Parse an IDS into a tree: a leaf is a str, a node is (operator, [children])."""
    toks = [t for t in TOKEN.findall(seq) if t != VARIATION]
    pos = 0

    def node():
        nonlocal pos
        if pos >= len(toks):
            raise IdsError("truncated")
        t = toks[pos]
        pos += 1
        if t in ARITY:
            return (t, [node() for _ in range(ARITY[t])])
        return t

    tree = node()
    if pos != len(toks):
        raise IdsError("trailing")
    return tree


def tree_text(tree) -> str:
    if isinstance(tree, str):
        return tree
    return tree[0] + "".join(tree_text(k) for k in tree[1])


def load_unihan():
    rs, strokes, has_def = {}, {}, set()
    with zipfile.ZipFile(source("unihan/Unihan.zip")) as zf:
        for name in ("Unihan_IRGSources.txt", "Unihan_Readings.txt"):
            with io.TextIOWrapper(zf.open(name), encoding="utf-8") as fh:
                for line in fh:
                    if not line.startswith("U+"):
                        continue
                    cp, field, value = line.rstrip("\n").split("\t", 2)
                    ch = chr(int(cp[2:], 16))
                    if field == "kRSUnicode":
                        rs[ch] = value.split()[0]
                    elif field == "kTotalStrokes":
                        strokes[ch] = int(value.split()[0])
                    elif field == "kDefinition":
                        has_def.add(ch)
    return rs, strokes, has_def


def load_radicals():
    """radical key ("120", "120'") -> (radical-block char or None, unified ideograph)."""
    out = {}
    for line in source("ucd/CJKRadicals.txt").read_text(encoding="utf-8").splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        key, rad, uni = [x.strip() for x in line.split(";")]
        out[key] = (chr(int(rad, 16)) if rad else None, chr(int(uni, 16)))
    return out


def load_equivalents():
    out = {}
    for line in source("ucd/EquivalentUnifiedIdeograph.txt").read_text(encoding="utf-8").splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        rng, uni = [x.strip() for x in line.split(";")]
        lo, _, hi = rng.partition("..")
        for cp in range(int(lo, 16), int(hi or lo, 16) + 1):
            out[chr(cp)] = chr(int(uni, 16))
    return out


def is_radical_form(ch: str) -> bool:
    return len(ch) == 1 and 0x2E80 <= ord(ch) <= 0x2FDF


def is_stroke(ch: str) -> bool:
    return len(ch) == 1 and 0x31C0 <= ord(ch) <= 0x31EF


def load_ids():
    """char -> [(regions, ids text)], {nn} -> description, {nn} -> encoded replacement fragment.

    The fragment is the file's own "IDS fragment" column for an unencoded component (`{48}` →
    `㐄`), an approximate encoded spelling; `？` or an empty column gives none.
    """
    seqs, unencoded, fragments = {}, {}, {}
    text = source("ids/IDS.TXT").read_text(encoding="utf-8-sig")
    for line in text.splitlines():
        if line.startswith("#"):
            m = re.match(r"#\t\{(\d+)\}\t([^\t]*)(?:\t([^\t]*))?", line)
            if m:
                desc = re.sub(r"\s*\([0-9A-F]{4,5}\s*\S?\s*\)\s*$", "", m.group(2)).strip()
                if desc:
                    unencoded[int(m.group(1))] = desc
                frag = (m.group(3) or "").strip()
                if frag and UNKNOWN not in frag:
                    fragments[int(m.group(1))] = frag
            continue
        cols = line.split("\t")
        if len(cols) < 3 or not cols[0].startswith("U+"):
            continue
        entries = []
        for col in cols[2:]:
            m = re.match(r"^\^(.+)\$\((.*)\)$", col.strip())
            if m:
                regions = re.sub(r"\[[^\]]*\]", "", m.group(2))
                entries.append((regions, m.group(1)))
        if entries:
            seqs[cols[1]] = entries
    return seqs, unencoded, fragments


def headword_chars():
    """(chars of simplified headwords, chars of traditional headwords)."""
    simp, trad = set(), set()
    for name in ("cedict_ts.u8", "cccanto-webdist.txt", "cccedict-canto-readings-150923.txt"):
        for t, s, _r, _l in read_cedict(REFS / name):
            simp.update(c for c in s if is_han(c))
            trad.update(c for c in t if is_han(c))
    return simp, trad


def build_decomposition() -> dict:
    rs, strokes, has_def = load_unihan()
    radicals = load_radicals()
    equiv = load_equivalents()
    ids_src, unencoded_desc, fragments = load_ids()
    mmah_decomp: dict[str, str] = {}
    with open(source("mmah/dictionary.txt"), encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                rec = json.loads(line)
                if rec.get("decomposition"):
                    mmah_decomp[rec["character"]] = rec["decomposition"]

    def norm(ch: str) -> str:
        """Radical forms -> equivalent unified ideograph; compatibility ideographs -> unified."""
        if len(ch) == 1 and (0xF900 <= ord(ch) <= 0xFAFF or 0x2F800 <= ord(ch) <= 0x2FA1F):
            uni = unicodedata.normalize("NFKC", ch)
            if len(uni) == 1:
                return uni
        return equiv.get(ch, ch)

    # ---- radical data -----------------------------------------------------------------
    def radical_key(ch: str):
        v = rs.get(ch)
        if not v:
            return None, None
        key, _, resid = v.partition(".")
        return key, int(resid) if resid.lstrip("-").isdigit() else None

    def radical_char(key: str):
        hit = radicals.get(key) or radicals.get(key.rstrip("'"))
        return hit[1] if hit else None

    radical_number_of_form = {}  # radical-block char / unified radical -> number
    for key, (rad, uni) in radicals.items():
        num = int(key.rstrip("'"))
        if rad:
            radical_number_of_form[rad] = num
        radical_number_of_form.setdefault(uni, num)

    def curated_name(ch: str):
        if ch in COMPONENT_NAMES:
            return COMPONENT_NAMES[ch]
        if len(ch) != 1:
            return None
        if ch in radical_number_of_form:
            return RADICAL_NAMES[radical_number_of_form[ch] - 1]
        base = norm(ch)
        if is_radical_form(ch) and base in radical_number_of_form:
            return RADICAL_NAMES[radical_number_of_form[base] - 1]
        key, resid = radical_key(base)
        if key and resid == 0:
            return RADICAL_NAMES[int(key.rstrip("'")) - 1]
        return None

    # ---- radical forms: which Kangxi radical(s) a glyph is written for ----------------------
    # The radical's ideograph and Kangxi code point, the CJK Radicals Supplement forms (by their
    # Unicode names: CJK RADICAL MEAT → KANGXI RADICAL MEAT) and their unified equivalents,
    # ideographs that Unihan files under a radical with no residual strokes (亻 9.0, 扌 64.0,
    # 王 96.-1) and the shared forms (月 for 肉, 阝 for 邑).
    kangxi_by_name = {}
    for key, (rad, uni) in radicals.items():
        if "'" not in key and rad:
            try:
                kangxi_by_name[unicodedata.name(rad).removeprefix("KANGXI RADICAL ")] = uni
            except ValueError:
                pass
    forms_of: dict[str, set[str]] = defaultdict(set)

    def add_form(form: str, uni: str) -> None:
        if form != uni:
            forms_of[form].add(uni)
        eq = norm(form)
        k, r = radical_key(eq)
        if eq != form and eq != uni and (k is None or (r is not None and r <= 0)):
            forms_of[eq].add(uni)

    for key, (rad, uni) in radicals.items():
        if rad:
            add_form(rad, uni)
    for cp in range(0x2E80, 0x2F00):
        try:
            name = unicodedata.name(chr(cp))
        except ValueError:
            continue
        base = re.sub(r" (?:ONE|TWO|THREE|FOUR)$", "",
                      re.sub(r"^CJK RADICAL (?:[CJ]-)?(?:SIMPLIFIED )?", "", name))
        if base in kangxi_by_name:
            add_form(chr(cp), kangxi_by_name[base])
    for ch in rs:
        key, resid = radical_key(ch)
        if key and resid is not None and resid <= 0:
            uni = radical_char(key)
            if uni and uni != ch:
                forms_of[ch].add(uni)
    for form, uni in SHARED_FORMS.items():
        forms_of[form].add(uni)

    def is_form(glyph: str, rad: str) -> bool:
        return glyph == rad or norm(glyph) == rad or rad in forms_of.get(glyph, ())

    def is_primitive(ch: str) -> bool:
        """Root rule (DATA-191): strokes, radical forms, the 214 radicals and their forms."""
        if is_stroke(ch) or is_radical_form(ch) or ch in radical_number_of_form:
            return True
        key, resid = radical_key(ch)
        return bool(key) and resid == 0

    # ---- sequences --------------------------------------------------------------------
    malformed = []
    parsed_cache: dict[tuple[str, str], object] = {}

    def parsed(ch: str, seq: str):
        key = (ch, seq)
        if key not in parsed_cache:
            try:
                parsed_cache[key] = parse_ids(seq)
            except IdsError as exc:
                parsed_cache[key] = None
                malformed.append(f"{ch} {seq} ({exc})")
        return parsed_cache[key]

    def candidates(ch: str, pref: str):
        """(source, tree) in order of preference: IDS.TXT for the preferred region, its other
        sequences in file order, Make Me a Hanzi's `decomposition`, then the IDS sequences with
        their unencoded components spelt by the file's replacement fragments."""
        entries = ids_src.get(ch) or []
        pick = None
        for region in (("G",) if pref == "G" else ("T", "H")):
            pick = next((e for e in entries if region in e[0]), None)
            if pick:
                break
        seqs = ([pick] if pick else []) + [e for e in entries if e is not pick]
        out = [("ids" if i == 0 and pick else "ids-other", parsed(ch, seq)) for i, (_r, seq) in enumerate(seqs)]
        if ch in mmah_decomp:
            out.append(("mmah", parsed(ch, mmah_decomp[ch])))
        for _r, seq in seqs:
            sub = re.sub(r"\{(\d+)\}", lambda m: fragments.get(int(m.group(1)), m.group(0)), seq)
            if sub != seq:
                out.append(("fragment", parsed(ch, sub)))
        return [(src, t) for src, t in out if t is not None]

    def is_unencoded(leaf: str) -> bool:
        return (leaf == UNKNOWN or bool(re.fullmatch(r"\{\d+\}", leaf))
                or (len(leaf) == 1 and 0xE000 <= ord(leaf) <= 0xF8FF))

    def shown_leaves(tree) -> list[str]:
        """The parts a reader sees for a tree: its slots, a nested sequence that is an encoded
        character as that character, any other nested sequence as its own slots (inline group)."""
        out = []
        for kid in tree[1]:
            if isinstance(kid, str):
                out.append(kid)
            elif tree_text(kid) in reverse:
                out.append(reverse[tree_text(kid)])
            else:
                out.extend(shown_leaves(kid))
        return out

    radical_cache: dict[tuple[str, str], bool] = {}

    def holds_radical(glyph: str, rad: str, depth: int) -> bool:
        """Is `glyph` the radical (or a form of it), or does its own preferred sequence hold it
        somewhere below? (A reader finds 口 in 同 = ⿵冂𠮛 by opening 𠮛.)"""
        if is_form(glyph, rad):
            return True
        if depth > 6 or is_unencoded(glyph) or is_primitive(glyph):
            return False
        key = (glyph, rad)
        if key not in radical_cache:
            radical_cache[key] = False
            base = norm(glyph)
            k, _r = radical_key(base)
            tree, _src = choose(base if base in ids_src or base in mmah_decomp else glyph, "G",
                                radical_char(k) if k else None)
            radical_cache[key] = (not isinstance(tree, str) and tree is not None
                                  and any(holds_radical(leaf, rad, depth + 1) for leaf in shown_leaves(tree)))
        return radical_cache[key]

    def rank(tree, ch: str, rad):
        """(tier, unencoded leaves, unknown leaves, inline groups): tier 0 = every part encoded and the radical
        among them or inside one of them, 1 = every part encoded, 2 = some part unencoded or unknown. None: unusable
        (the character is its own part)."""
        leaves = shown_leaves(tree)
        if ch in leaves or norm(ch) in leaves:
            return None
        unencoded = sum(1 for leaf in leaves if is_unencoded(leaf))
        has_radical = rad is None or rad == ch or any(holds_radical(leaf, rad, 0) for leaf in leaves)
        groups = sum(1 for k in tree[1] if not isinstance(k, str) and tree_text(k) not in reverse)
        unknown = leaves.count(UNKNOWN)
        if unencoded:  # a described unencoded component ({25}) beats an unknown one (？)
            return (2, unencoded, unknown, 0)
        return (0 if has_radical else 1, 0, 0, groups)

    chosen_cache: dict[tuple[str, str], tuple] = {}

    def choose(ch: str, pref: str, rad):
        key = (ch, pref)
        if key not in chosen_cache:
            chosen_cache[key] = (None, None)  # a cycle reads as "no sequence"
            chosen_cache[key] = choose_uncached(ch, pref, rad)
        return chosen_cache[key]

    def choose_uncached(ch: str, pref: str, rad):
        """The IDS tree of `ch` (DATA-191, one sequence per character). The preferred region's
        sequence unless it falls short — an unencoded or unknown part, or the character's radical
        neither a part nor inside one — then the best candidate by rank (tier, unencoded parts,
        unknown parts, inline groups), then order. A preferred sequence that is the character itself keeps it a root."""
        cands = candidates(ch, pref)
        if not cands:
            return None, None
        if isinstance(cands[0][1], str):
            return cands[0][1], cands[0][0]
        scored = []
        for i, (src, tree) in enumerate(cands):
            if isinstance(tree, str):
                continue
            r = rank(tree, ch, rad)
            if r is not None:
                scored.append((r, i, src, tree))
        if not scored:
            return None, None
        if scored[0][1] == 0 and scored[0][0][0] == 0:
            return scored[0][3], scored[0][2]
        best = min(scored, key=lambda x: (x[0], x[1]))
        return best[3], best[2]

    simp_chars, trad_chars = headword_chars()
    dict_chars = simp_chars | trad_chars

    # A nested sub-sequence is replaced by the encoded character with exactly that sequence
    # when there is a well-supported one (a dictionary character or a BMP ideograph, lowest
    # code point); otherwise it stays nested in the parent's IDS.
    reverse: dict[str, str] = dict(NESTED_ALIASES)
    for ch in sorted(ids_src):
        if ord(ch) > 0xFFFF and ch not in dict_chars:
            continue
        for regions, seq in ids_src[ch]:
            if seq and seq[0] in ARITY and "？" not in seq:
                reverse.setdefault(seq.replace(VARIATION, ""), ch)

    rows: dict[str, dict] = {}
    region_used = defaultdict(int)
    source_used = defaultdict(int)
    anonymous = 0

    def synthetic_for(tree) -> str:
        return "&U-s" + hashlib.sha256(tree_text(tree).encode("utf-8")).hexdigest()[:6] + ";"

    def part_id(kid, pref: str, queue: list) -> str:
        nonlocal anonymous
        if isinstance(kid, str):
            m = re.fullmatch(r"\{(\d+)\}", kid)
            if m:
                pid = f"&U-i{int(m.group(1)):03d};"
                if pid not in rows:
                    rows[pid] = dict(ids="", parts=[], radical=None, strokes=None,
                                     name=unencoded_desc.get(int(m.group(1))), svg=None)
                return pid
            if kid != UNKNOWN:
                queue.append((kid, pref))
            return kid
        text = tree_text(kid)
        hit = reverse.get(text)
        if hit:
            queue.append((hit, pref))
            return hit
        pid = synthetic_for(kid)
        if pid not in rows:
            anonymous += 1
            rows[pid] = None  # reserve, then fill
            parts = [part_id(k, pref, queue) for k in kid[1]]
            rows[pid] = dict(ids=kid[0] + "".join(parts), parts=parts, radical=None,
                             strokes=None, name=None, svg=None)
        return pid

    def add_char(ch: str, pref: str, queue: list) -> None:
        if ch in rows:
            return
        base = norm(ch)
        key, _resid = radical_key(base)
        row = dict(ids="", parts=[], radical=radical_char(key) if key else None,
                   strokes=strokes.get(base), name=curated_name(ch), svg=None)
        rows[ch] = row
        if is_primitive(ch):
            return
        tree, src = choose(base if base in ids_src or base in mmah_decomp else ch, pref, row["radical"])
        if tree is None or isinstance(tree, str):
            return  # no sequence, or the sequence is the character itself / an alias: root
        region = next((r for r in (("G",) if pref == "G" else ("T", "H"))
                       if any(r in e[0] for e in ids_src.get(ch) or ids_src.get(base) or [])), "default")
        region_used[region] += 1
        parts = [part_id(k, pref, queue) for k in tree[1]]
        if ch in parts or base in parts:
            return
        source_used[src] += 1
        if src != "ids":
            chosen_alt[ch] = (src, tree_text(tree))
        row["ids"] = tree[0] + "".join(parts)
        row["parts"] = parts

    unihan_only = {c for c in has_def if c not in dict_chars}
    chosen_alt: dict[str, tuple[str, str]] = {}
    queue: list = []
    # Region (ENT-124, one sequence per character here): G for characters of simplified
    # headwords and everything reached from them; T (then H) for traditional-only characters.
    for group, pref in ((simp_chars, "G"), (trad_chars - simp_chars, "T"), (unihan_only, "G")):
        queue.extend((c, pref) for c in sorted(group))
        while queue:
            ch, p = queue.pop()
            add_char(ch, p, queue)

    # ---- cycles: a component recurring on its own path becomes a root --------------------
    cycles: list[str] = []
    done: set[str] = set()
    sys.setrecursionlimit(10000)

    def visit(cid: str, path: frozenset) -> None:
        here = path | {cid}
        for p in list(rows[cid]["parts"]):
            if p in here:
                cycles.append(cid)
                rows[cid]["ids"], rows[cid]["parts"] = "", []
                break
            if p in rows and p not in done:
                visit(p, here)
        done.add(cid)

    for cid in sorted(rows):
        if cid not in done:
            visit(cid, frozenset())

    depth_cache: dict[str, int] = {}

    def depth(cid: str) -> int:
        if cid not in depth_cache:
            depth_cache[cid] = 0
            parts = rows.get(cid, {}).get("parts") or []
            depth_cache[cid] = 1 + max((depth(p) for p in parts), default=-1) if parts else 0
        return depth_cache[cid]

    max_depth = max(depth(c) for c in rows)

    # ---- Make Me a Hanzi matches (DATA-197) ---------------------------------------------
    def norm_cmp(ch: str) -> str:
        """Fold radical forms onto their radical (訁 → 言, 亻 → 人) for comparing components."""
        base = norm(ch)
        key, resid = radical_key(base)
        return (radical_char(key) or base) if key and resid == 0 else base

    stroke_cache: dict[str, int | None] = {}

    def est_strokes(cid: str):
        """Stroke count of a part: kTotalStrokes, else the sum over its own parts."""
        if cid not in stroke_cache:
            row = rows.get(cid)
            total = None
            if row is not None:
                total = row["strokes"]
                if total is None and row["parts"]:
                    sub = [est_strokes(p) for p in row["parts"]]
                    total = None if None in sub else sum(sub)
            stroke_cache[cid] = total
        return stroke_cache[cid]

    def node_ok(mnode, cid: str, prefix: list, matches: list) -> bool:
        """Does Make Me a Hanzi's node split the glyph into the same parts as our stored row?

        Same number of parts, and every slot holds the same component — or, where the two
        sources name the component differently (variant forms, a nested sequence against an
        encoded character), the strokes MMAH assigns to the slot equal our part's stroke count.
        A slot that cannot be checked either way is accepted only under the same operator.
        """
        row = rows.get(cid)
        if isinstance(mnode, str) or not row or not row["parts"]:
            return False
        if len(row["parts"]) != len(mnode[1]):
            return False
        same_op = row["ids"][0] == mnode[0]
        k = len(prefix)
        counts = defaultdict(int)
        for path in matches:
            if path and len(path) > k and path[:k] == prefix:
                counts[path[k]] += 1
        verified = unverified = 0
        for i, (kid, part) in enumerate(zip(mnode[1], row["parts"])):
            if isinstance(kid, str) and kid != UNKNOWN and norm_cmp(kid) == norm_cmp(part):
                verified += 1
                continue
            n = est_strokes(part)
            if n is None:
                unverified += 1  # unencoded part: it takes what the verified slots leave
            elif n != counts[i]:
                return False
            else:
                verified += 1
        return unverified == 0 or (same_op and verified > 0)

    PAIR = {"⿱": "⿳", "⿰": "⿲"}
    UNPAIR = {v: k for k, v in PAIR.items()}

    def regroupings(mtree, matches):
        """MMAH's tree as given, then the equivalent flat / nested spellings of a split in
        three (⿱⿱AB C = ⿳ABC = ⿱A⿱BC), each with the match paths rewritten to fit."""
        yield mtree, matches
        if isinstance(mtree, str):
            return
        op, kids = mtree

        def rewrite(fn):
            return [None if p is None else fn(p) for p in matches]

        if op in PAIR and len(kids) == 2:
            for j in (0, 1):
                kid = kids[j]
                if isinstance(kid, str) or kid[0] != op or len(kid[1]) != 2:
                    continue

                def flat(p, j=j):
                    if p[0] != j:
                        return [0 if j else 2] + p[1:]
                    return [p[1] + j] + p[2:] if len(p) > 1 else None

                yield ((PAIR[op], kid[1] + [kids[1]] if j == 0 else [kids[0]] + kid[1]),
                       rewrite(flat))
        elif op in UNPAIR and len(kids) == 3:
            op2 = UNPAIR[op]
            yield ((op2, [(op2, kids[:2]), kids[2]]),
                   rewrite(lambda p: ([0, p[0]] if p[0] < 2 else [1]) + p[1:]))
            yield ((op2, [kids[0], (op2, kids[1:])]),
                   rewrite(lambda p: ([0] if p[0] == 0 else [1, p[0] - 1]) + p[1:]))

    mm_stats = defaultdict(int)
    with open(source("mmah/dictionary.txt"), encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            ch = rec["character"]
            row = rows.get(ch)
            if row is None:
                continue
            mm_stats["characters_in_mmah"] += 1
            matches = rec.get("matches")
            if not matches or all(m is None for m in matches):
                mm_stats["no_matches_field"] += 1
                continue
            if not row["parts"]:
                mm_stats["root_in_pack"] += 1
                continue
            try:
                mtree = parse_ids(rec.get("decomposition") or "")
            except IdsError:
                mm_stats["mmah_ids_malformed"] += 1
                continue
            for mtree, matches in regroupings(mtree, matches):
                if node_ok(mtree, ch, [], matches):
                    break
            else:
                mm_stats["split_mismatch"] += 1
                continue
            out, truncated = [], False
            for path in matches:
                if path is None:
                    out.append(None)
                    continue
                keep, mnode, cid = [], mtree, ch
                for depth_i, idx in enumerate(path):
                    if depth_i > 0 and not node_ok(mnode, cid, keep, matches):
                        break
                    if isinstance(mnode, str) or idx >= len(mnode[1]):
                        break
                    keep.append(idx)
                    mnode, cid = mnode[1][idx], rows[cid]["parts"][idx]
                if len(keep) < len(path):
                    truncated = True
                out.append(keep or None)
            if row["strokes"] is not None and row["strokes"] != len(out):
                mm_stats["stroke_count_differs_from_kTotalStrokes"] += 1
            row["matches"] = jdump(out)
            mm_stats["with_matches"] += 1
            if truncated:
                mm_stats["with_truncated_paths"] += 1

    # ---- write ------------------------------------------------------------------------
    path = OUT / "decomposition.db"
    db = open_db(path)
    db.execute(
        "CREATE TABLE decomp(ch TEXT PRIMARY KEY, ids TEXT, parts TEXT, radical TEXT, "
        "strokes INTEGER, name TEXT, svg TEXT, matches TEXT) WITHOUT ROWID")

    def inline(pid: str) -> str:
        """An anonymous nested sub-sequence is written inline, as IDS text, in its parent."""
        if not pid.startswith("&U-s"):
            return pid
        r = rows[pid]
        return r["ids"][0] + "".join(inline(p) for p in r["parts"])

    out_rows = []
    for cid, r in sorted(rows.items()):
        if cid.startswith("&U-s"):
            continue
        parts = [inline(p) for p in r["parts"]]
        out_rows.append((cid, r["ids"][0] + "".join(parts) if parts else "", jdump(parts),
                         r["radical"], r["strokes"], r["name"], r["svg"], r.get("matches")))
    db.executemany("INSERT INTO decomp VALUES(?,?,?,?,?,?,?,?)", out_rows)

    # Reverse index (PARTS tab "Appears in"): every leaf of a row's own IDS — operators skipped,
    # inline nested sub-sequences flattened, `？` dropped — lists that row under the leaf.
    db.execute("CREATE TABLE part_of(part TEXT PRIMARY KEY, chars TEXT NOT NULL) WITHOUT ROWID")
    leaf_token = re.compile(r"&[^;]+;|.", re.S)
    users: dict[str, set[str]] = defaultdict(set)
    for cid, ids, *_rest in out_rows:
        for tok in leaf_token.findall(ids):
            if tok in ARITY or tok in (UNKNOWN, VARIATION) or tok == cid:
                continue
            users[tok].add(cid)
    db.executemany("INSERT INTO part_of VALUES(?,?)",
                   sorted((p, "".join(sorted(cs))) for p, cs in users.items()))

    # Written forms of Kangxi radicals (PARTS tab radical marks, SCHEMA note 24): every row glyph
    # that stands for a radical other than itself (亻 → 人, 月 → 月's 74 and 肉, 阝 → 阜 and 邑).
    db.execute("CREATE TABLE radical_form(form TEXT NOT NULL, radical TEXT NOT NULL, "
               "PRIMARY KEY(form, radical)) WITHOUT ROWID")
    form_rows = sorted({(f, r) for f in rows if not f.startswith("&") for r in forms_of.get(f, ())})
    db.executemany("INSERT INTO radical_form VALUES(?,?)", form_rows)

    stats = {
        "rows": len(out_rows),
        "dictionary_headword_chars": len(dict_chars),
        "dictionary_chars_with_row": sum(1 for c in dict_chars if c in rows),
        "dictionary_chars_decomposed": sum(1 for c in dict_chars if rows[c]["parts"]),
        "dictionary_chars_root": sum(1 for c in dict_chars if not rows[c]["parts"]),
        "dictionary_chars_with_matches": sum(1 for c in dict_chars if rows[c].get("matches")),
        "unihan_only_chars": len(unihan_only),
        "rows_decomposed": sum(1 for r in out_rows if r[1]),
        "rows_with_matches": sum(1 for r in rows.values() if r.get("matches")),
        "rows_with_name": sum(1 for r in rows.values() if r["name"]),
        "rows_with_radical": sum(1 for r in rows.values() if r["radical"]),
        "rows_with_strokes": sum(1 for r in rows.values() if r["strokes"] is not None),
        "unencoded_components": sum(1 for c in rows if c.startswith("&U-i")),
        "nested_subsequences_inline": sum(1 for c in rows if c.startswith("&U-s")),
        "unknown_part_refs": sum(r["parts"].count(UNKNOWN) for r in rows.values()),
        "part_of_rows": len(users),
        "part_of_pairs": sum(len(cs) for cs in users.values()),
        "part_of_max_chars": max((len(cs) for cs in users.values()), default=0),
        "dictionary_chars_without_ids_source": sorted(
            c for c in dict_chars if c not in ids_src)[:200],
        "region_used": dict(sorted(region_used.items())),
        "sequence_source": dict(sorted(source_used.items())),
        "dictionary_chars_alternative_sequence": sum(1 for c in dict_chars if c in chosen_alt),
        "alternative_sequence_examples": {c: f"{v[0]} {v[1]}" for c, v in sorted(
            ((c, v) for c, v in chosen_alt.items() if c in simp_chars), key=lambda x: x[0])[:60]},
        "radical_form_rows": len(form_rows),
        "cycles_broken": cycles,
        "max_depth": max_depth,
        "malformed_sequences": sorted(set(malformed))[:100],
        "mmah": dict(sorted(mm_stats.items())),
    }
    write_meta(db, {
        "pack": "decomposition",
        "source": "BabelStone IDS.TXT; Unicode Unihan/UCD; Make Me a Hanzi dictionary.txt "
                  "(matches, alternative sequences)",
        "rows": str(stats["rows"]), "rows_with_matches": str(stats["rows_with_matches"]),
        "part_of_rows": str(stats["part_of_rows"]),
    })
    close_db(db)
    stats["bytes"] = path.stat().st_size
    log("decomposition.db: " + jdump({k: v for k, v in stats.items()
                                      if not isinstance(v, list)}))
    return stats


# --------------------------------------------------------------------------------------
# audio
# --------------------------------------------------------------------------------------

AUDIO_SETS = {
    # dir -> (source folder per format, tone digits)
    "cmn-syl": ({"opus": "mandarin_syllables_voice1_opus_48k", "aac": "mandarin_syllables_voice1_aac_48k"}, "1-5"),
    "cmn-word": ({"opus": "mandarin_words_opus_48k", "aac": "mandarin_words_aac_48k"}, "1-5"),
    "yue-syl": ({"opus": "cantonese_syllables_voice1_opus_24k", "aac": "cantonese_syllables_voice1_aac_48k"}, "1-6"),
}
SOURCE_EXT = {"opus": ".opus", "aac": ".m4a"}
# key -> existing key whose clip also serves it. CC-CEDICT writes erhua as its own syllable
# `r5` (yi1 dian3 r5); the syllable set records it as `er5`.
AUDIO_ALIASES = {"cmn-syl": {"r5": "er5"}}


def audio_key(stem: str, mandarin: bool):
    """Normalise a source file stem to the contract key (MED-005); None when unusable."""
    s = stem.strip().lower().replace(" ", "_").replace("-", "_")
    if mandarin:
        s = s.replace("u:", "v").replace("ü", "v")
        s = re.sub(r"(?<=[a-z])0(?=_|$)", "5", s)  # neutral tone written 0
        pat = r"[a-z]+[1-5](_[a-z]+[1-5])*"
    else:
        pat = r"[a-z]+[1-6]"
    return s if re.fullmatch(pat, s) else None


def reading_key(reading: str):
    """A CC-CEDICT reading -> canonical key syllables, or None if any syllable is untoned."""
    syls = []
    for syl in reading.split():
        s = syl.lower().replace("u:", "v").replace("ü", "v")
        if not re.fullmatch(r"[a-z]+[1-5]", s):
            return None
        syls.append(s)
    return syls or None


def build_audio(fmt: str, ext: str) -> dict:
    out = OUT / "audio"
    if out.exists():
        shutil.rmtree(out)
    stats: dict = {"format": fmt, "ext": ext}
    keys: dict[str, list[str]] = {}
    for name, (folders, _tones) in AUDIO_SETS.items():
        src_dir = AUDIO_REPO / folders[fmt]
        dst_dir = out / name
        dst_dir.mkdir(parents=True)
        seen, skipped, size = {}, [], 0
        for f in sorted(src_dir.iterdir()):
            if f.suffix != SOURCE_EXT[fmt]:
                continue
            key = audio_key(f.stem, mandarin=name.startswith("cmn"))
            if key is None or key in seen:
                skipped.append(f.name)
                continue
            if name == "cmn-word" and "_" not in key:
                skipped.append(f.name)
                continue
            seen[key] = f
            shutil.copyfile(f, dst_dir / f"{key}.{ext}")  # sources are never modified (PIPE-062)
            size += f.stat().st_size
        aliased = []
        for alias, target in sorted(AUDIO_ALIASES.get(name, {}).items()):
            if alias not in seen and target in seen:
                seen[alias] = seen[target]
                shutil.copyfile(seen[target], dst_dir / f"{alias}.{ext}")
                size += seen[target].stat().st_size
                aliased.append(f"{alias}={target}")
        keys[name] = sorted(seen)
        stats[name] = {"files": len(seen), "bytes": size, "skipped": skipped, "aliases": aliased}
    catalog = {
        "mandarin": {"syllables": "cmn-syl", "words": "cmn-word", "ext": ext,
                     "syllable_keys": keys["cmn-syl"], "word_keys": keys["cmn-word"]},
        "cantonese": {"syllables": "yue-syl", "ext": ext, "syllable_keys": keys["yue-syl"]},
    }
    (out / "catalog.json").write_text(jdump(catalog), encoding="utf-8")
    stats["total_bytes"] = sum(stats[n]["bytes"] for n in AUDIO_SETS)

    # ---- coverage against the dictionaries' readings -----------------------------------
    cmn_syl, cmn_word = set(keys["cmn-syl"]), set(keys["cmn-word"])

    def syl_ok(s: str) -> bool:  # MED-007: neutral tone may fall back to tone 1
        return s in cmn_syl or (s.endswith("5") and s[:-1] + "1" in cmn_syl)

    entries = multi = multi_word = full_syl = playable = keyed = 0
    need_syl, word_keys_needed = set(), set()
    for _t, s, reading, _l in read_cedict(REFS / "cedict_ts.u8"):
        if not has_han(s):
            continue
        entries += 1
        syls = reading_key(reading)
        if syls is None:
            continue
        keyed += 1
        need_syl.update(syls)
        has_word = False
        if len(syls) > 1:
            multi += 1
            word_keys_needed.add("_".join(syls))
            has_word = "_".join(syls) in cmn_word
            multi_word += has_word
        all_syl = all(syl_ok(x) for x in syls)
        full_syl += all_syl
        playable += has_word or all_syl
    stats["mandarin_coverage"] = {
        "cedict_entries": entries,
        "entries_with_canonical_key": keyed,
        "distinct_syllables_expected": len(need_syl),
        "distinct_syllables_with_clip": sum(1 for x in need_syl if x in cmn_syl),
        "distinct_syllables_with_clip_or_tone1_fallback": sum(1 for x in need_syl if syl_ok(x)),
        "syllables_missing": sorted(x for x in need_syl if not syl_ok(x)),
        "multi_syllable_entries": multi,
        "multi_syllable_entries_with_word_clip": multi_word,
        "distinct_word_keys_expected": len(word_keys_needed),
        "word_clips_matching_a_cedict_key": len(cmn_word & word_keys_needed),
        "word_clips_not_matching_any_cedict_key": len(cmn_word - word_keys_needed),
        "entries_fully_covered_by_syllable_clips": full_syl,
        "entries_playable_word_or_syllables": playable,
    }

    yue_syl = set(keys["yue-syl"])
    y_entries = y_keyed = y_full = 0
    y_need = set()
    seen_lines = set()
    for name in ("cccanto-webdist.txt", "cccedict-canto-readings-150923.txt"):
        for t, _s, _r, line in read_cedict(REFS / name):
            m = re.search(r"\{([^}]*)\}", line)
            if not m or (t, m.group(1)) in seen_lines:
                continue
            seen_lines.add((t, m.group(1)))
            y_entries += 1
            syls = [x.lower() for x in m.group(1).split()]
            if not syls or not all(re.fullmatch(r"[a-z]+[1-6]", x) for x in syls):
                continue
            y_keyed += 1
            y_need.update(syls)
            y_full += all(x in yue_syl for x in syls)
    stats["cantonese_coverage"] = {
        "entries_with_jyutping": y_entries,
        "entries_with_canonical_key": y_keyed,
        "entries_fully_covered_by_syllable_clips": y_full,
        "distinct_syllables_expected": len(y_need),
        "distinct_syllables_with_clip": len(y_need & yue_syl),
        "syllables_missing": sorted(y_need - yue_syl),
    }
    log("audio: " + jdump({k: v for k, v in stats.items() if not k.endswith("coverage")}))
    return stats


# --------------------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--only", choices=["sentences", "decomposition", "audio"])
    ap.add_argument("--audio-format", choices=["opus", "aac"], default="opus",
                    help="opus: Ogg Opus copied as-is (default); aac: AAC-LC in .m4a")
    ap.add_argument("--audio-ext", default=None,
                    help="file extension for the copies (default: ogg for opus, m4a for aac)")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.only in (None, "sentences"):
        update_report("sentences", build_sentences_cmn())
        update_report("sentences-yue", build_sentences_yue())
    if args.only in (None, "decomposition"):
        update_report("decomposition", build_decomposition())
    if args.only in (None, "audio"):
        ext = args.audio_ext or ("ogg" if args.audio_format == "opus" else "m4a")
        update_report("audio", build_audio(args.audio_format, ext))


if __name__ == "__main__":
    main()
