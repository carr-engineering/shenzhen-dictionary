"""Checks on the built packs. Build first (`uv run pipeline/packs/build_packs.py`), then:

    uv run --with pytest pytest pipeline/packs/test_packs.py
"""
import json
import sqlite3
from pathlib import Path

import pytest

PACKS = Path(__file__).resolve().parents[1] / "out" / "packs"


def _db(name):
    path = PACKS / name
    if not path.exists():
        pytest.skip(f"{name} not built")
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def _varints(blob):
    out, n, shift = [], 0, 0
    for b in blob:
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            out.append(n)
            n, shift = 0, 0
    assert shift == 0, "truncated varint"
    return out


def _examples(db, word):
    row = db.execute("SELECT ids FROM word_sentences WHERE word = ?", (word,)).fetchone()
    assert row, f"no sentences for {word}"
    ids = _varints(row[0])
    assert 0 < len(ids) <= 20 and len(set(ids)) == len(ids)
    rows = [db.execute("SELECT zh, zh_alt, en FROM sentence WHERE id = ?", (i,)).fetchone() for i in ids]
    assert all(rows), "index points at a missing sentence"
    return rows


@pytest.mark.parametrize("word", ["你好", "研究"])
def test_mandarin_sentences(word):
    db = _db("sentences.db")
    rows = _examples(db, word)
    for zh, zh_alt, en in rows:
        assert word in zh and zh_alt and en
    # ranked: the top example is a short one, and the list is not longest-first
    lengths = [len(zh) for zh, _, _ in rows]
    assert lengths[0] <= 12
    assert lengths[0] == min(lengths[:5])


def test_mandarin_scripts_and_standalone_first():
    db = _db("sentences.db")
    # 研究 standalone before 研究 inside 研究生 / 研究所
    zhs = [zh for zh, _, _ in _examples(db, "研究")]
    inside = [i for i, zh in enumerate(zhs) if "研究生" in zh or "研究所" in zh or "研究员" in zh]
    plain = [i for i in range(len(zhs)) if i not in inside]
    assert plain and (not inside or min(inside) > min(plain))
    # zh simplified, zh_alt traditional
    zh, zh_alt, _ = next(r for r in _examples(db, "说") if "说" in r[0])
    assert "說" in zh_alt and "說" not in zh
    assert db.execute("PRAGMA user_version").fetchone()[0] == 1


def test_cantonese_sentences():
    db = _db("sentences-yue.db")
    rows = _examples(db, "唔")
    assert all("唔" in zh and en for zh, _, en in rows)
    assert all(len(zh) <= 20 for zh, _, _ in rows)
    zh, zh_alt, _ = _examples(db, "聽日")[0]
    assert "聽日" in zh and "听日" in zh_alt


def _decomp(db, ch):
    row = db.execute(
        "SELECT ids, parts, radical, strokes, name, svg, matches FROM decomp WHERE ch = ?", (ch,)
    ).fetchone()
    assert row, f"no decomposition row for {ch}"
    return dict(zip(("ids", "parts", "radical", "strokes", "name", "svg", "matches"), row))


def test_decomposition_hao():
    db = _db("decomposition.db")
    hao = _decomp(db, "好")
    assert hao["ids"] == "⿰女子"
    assert json.loads(hao["parts"]) == ["女", "子"]
    assert hao["radical"] == "女" and hao["strokes"] == 6
    matches = json.loads(hao["matches"])
    assert len(matches) == hao["strokes"]
    assert matches == [[0], [0], [0], [1], [1], [1]]
    assert _decomp(db, "女")["name"] == "woman"
    assert _decomp(db, "氵")["name"].startswith("water")


OPERATORS = "⿰⿱⿲⿳⿴⿵⿶⿷⿸⿹⿺⿻⿼⿽⿾⿿㇯"


def _leaves(part):
    """Leaf components of a part: itself, or the leaves of a nested IDS written inline."""
    if part[0] not in OPERATORS:
        return [part]
    import re
    return [t for t in re.findall(r"&[^;]*;|.", part) if t not in OPERATORS]


def test_decomposition_recurses_to_roots():
    db = _db("decomposition.db")

    def walk(ch, path=()):
        assert ch not in path, f"cycle at {ch}"
        row = _decomp(db, ch)
        parts = json.loads(row["parts"])
        if not parts:
            assert row["ids"] == ""
            return {ch}
        assert row["ids"][0] in OPERATORS
        assert row["ids"] == row["ids"][0] + "".join(parts)
        roots = set()
        for part in parts:
            for leaf in _leaves(part):
                if leaf != "？":
                    roots |= walk(leaf, path + (ch,))
        return roots

    ni = _decomp(db, "你")
    assert json.loads(ni["parts"]) == ["亻", "尔"]
    roots = walk("你")
    assert "亻" in roots and "小" in roots
    assert walk("想") == {"木", "目", "心"}
    for ch in "說说龍龙麵鬱樂我":
        walk(ch)


def test_decomposition_prefers_encoded_sequences_with_the_radical():
    """我 is ⿻⿱㇒扌{97} in IDS.TXT's regional sequence; its alternative 〾⿰⿱㇒扌戈 (the hand
    written as 手) has every part encoded and holds the radical 戈, so it is the one stored."""
    db = _db("decomposition.db")
    wo = _decomp(db, "我")
    assert json.loads(wo["parts"]) == ["手", "戈"] and wo["radical"] == "戈"
    for ch, parts in {"会": ["𠆢", "云"], "在": ["才", "土"], "岛": ["鸟", "山"], "街": ["行", "圭"]}.items():
        assert json.loads(_decomp(db, ch)["parts"]) == parts, ch
    # No unencoded part where the preferred sequence was complete (好, 你 unchanged).
    assert json.loads(_decomp(db, "你")["parts"]) == ["亻", "尔"]
    # The reverse index follows the stored sequences.
    for part in ("手", "戈"):
        chars = db.execute("SELECT chars FROM part_of WHERE part = ?", (part,)).fetchone()[0]
        assert "我" in chars
    assert "我" not in (db.execute("SELECT chars FROM part_of WHERE part = '&U-i097;'").fetchone() or [""])[0]


def test_decomposition_radical_forms():
    db = _db("decomposition.db")
    forms = set(db.execute("SELECT form, radical FROM radical_form"))
    assert {("亻", "人"), ("扌", "手"), ("月", "肉"), ("阝", "邑"), ("阝", "阜"), ("耂", "老")} <= forms
    assert not any(f == r for f, r in forms)


def test_decomposition_matches_are_valid_paths():
    """A match path walks slots: through nested IDS inline, then into the part's own row."""
    db = _db("decomposition.db")
    import re
    ids_of = {}

    def tree(ids):
        toks = re.findall(r"&[^;]*;|.", ids)
        pos = 0

        def node():
            nonlocal pos
            t = toks[pos]
            pos += 1
            if t in OPERATORS:
                return [node() for _ in range(3 if t in "⿲⿳" else 1 if t in "⿾⿿" else 2)]
            return t

        return node()

    def kids(node):
        if isinstance(node, list):
            return node
        if node not in ids_of:
            row = db.execute("SELECT ids FROM decomp WHERE ch = ?", (node,)).fetchone()
            ids_of[node] = tree(row[0]) if row and row[0] else []
        return ids_of[node]

    n = 0
    for ch, matches in db.execute("SELECT ch, matches FROM decomp WHERE matches IS NOT NULL"):
        n += 1
        for path in json.loads(matches):
            cur = ch
            for idx in path or ():
                assert idx < len(kids(cur)), (ch, path)
                cur = kids(cur)[idx]
    assert n > 5000


def test_audio_catalogue():
    cat_path = PACKS / "audio" / "catalog.json"
    if not cat_path.exists():
        pytest.skip("audio not built")
    cat = json.loads(cat_path.read_text(encoding="utf-8"))
    cmn, yue = cat["mandarin"], cat["cantonese"]
    assert {"ni3", "hao3", "lv4"} <= set(cmn["syllable_keys"])
    assert "peng2_you5" in cmn["word_keys"]
    assert "gaa3" in yue["syllable_keys"]
    for lang, field, keys in (
        (cmn, "syllables", ["ni3", "hao3"]),
        (cmn, "words", ["peng2_you5"]),
        (yue, "syllables", ["gaa3"]),
    ):
        for key in keys:
            f = PACKS / "audio" / lang[field] / f"{key}.{lang['ext']}"
            assert f.is_file() and f.stat().st_size > 500, f
    # every catalogued key has its file, and nothing else is in the folders
    for lang, field, kfield in (
        (cmn, "syllables", "syllable_keys"), (cmn, "words", "word_keys"), (yue, "syllables", "syllable_keys")
    ):
        on_disk = sorted(p.stem for p in (PACKS / "audio" / lang[field]).iterdir())
        assert on_disk == sorted(lang[kfield])
        assert all(k == k.lower() and ":" not in k and "ü" not in k for k in on_disk)
