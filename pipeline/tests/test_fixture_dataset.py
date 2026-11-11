"""Builds the fixture (ARCH-151) and checks the written files against contract/SCHEMA.md."""
import json
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

from szdict.core import expand_body
from szdict.enc import body_dict, derive_reading_tokens, read_delta_list, read_varints, unzjson

PIPELINE = Path(__file__).resolve().parents[1]
OUT = PIPELINE / "out" / "fixture"


@pytest.fixture(scope="module")
def built():
    r = subprocess.run([sys.executable, str(PIPELINE / "build.py"), "--fixture"], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
    return OUT


def db(path):
    return sqlite3.connect(f"file:{path}?mode=ro", uri=True)


def entry(con, simp, reading=None):
    q = "SELECT id, trad, simp, reading_display, reading_canonical, flags, hsk, hsk_via, body FROM entry WHERE simp = ?"
    rows = con.execute(q, (simp,)).fetchall()
    if reading is not None:
        rows = [r for r in rows if r[4] == reading]
    assert rows, simp
    r = rows[0]
    from szdict.roman import parse_pinyin
    display = parse_pinyin(r[4])[0] if r[3] is None else r[3]     # schema version 2: NULL display = re-marked canonical
    body = expand_body(unzjson(r[8], body_dict(con)), display, r[4])
    return {"id": r[0], "trad": r[1], "simp": r[2], "display": display, "canonical": r[4], "flags": r[5],
            "hsk": json.loads(r[6] or "[]"), "hsk_via": json.loads(r[7] or "[]"),
            "gloss": "; ".join(x["t"] for x in body["senses"]), "body": body}


def test_committed_fixture_is_current(built):
    committed = PIPELINE.parent / "contract" / "fixture"
    for name in ("mandarin.db", "mandarin-lm.db", "cantonese.db", "cantonese-lm.db", "mandarin-widget.json",
                 "cantonese-widget.json"):
        assert (committed / name).read_bytes() == (built / name).read_bytes(), f"{name}: rebuild and commit contract/fixture"


def test_mandarin_entry_and_body(built):
    con = db(built / "mandarin.db")
    e = entry(con, "你好")
    assert e["display"] == "nǐ hǎo" and e["canonical"] == "ni3 hao3" and e["hsk"][0]["l"] == 1
    assert e["body"]["senses"][0]["t"].startswith("hello")
    assert e["body"]["blocks"][0]["kind"] == "mandarin" and e["body"]["blocks"][0]["readings"][0]["c"] == "ni3 hao3"
    assert con.execute("PRAGMA user_version").fetchone()[0] == 2
    meta = dict(con.execute("SELECT key, value FROM meta"))
    assert meta["dataset_id"] == "mandarin" and meta["romanization"] == "pinyin" and meta["tone_count"] == "5"
    assert int(meta["entry_count"]) == con.execute("SELECT count(*) FROM entry").fetchone()[0]


def test_indexes(built):
    con = db(built / "mandarin.db")
    eid = entry(con, "你好")["id"]
    assert (eid, 0, 0, bytes([3, 3])) in con.execute("SELECT entry_id, alt, alt_note, tones FROM reading WHERE key = 'ni hao'").fetchall()
    assert con.execute("SELECT kind FROM headword WHERE key = 't恤'").fetchall() == [(2,)]
    assert con.execute("SELECT kind FROM headword WHERE key = 'T恤'").fetchall() == [(0,)]
    love = read_varints(con.execute("SELECT postings FROM en_token WHERE token = 'love'").fetchone()[0])
    ai = entry(con, "爱")
    ids, acc = [], 0
    for i in range(0, len(love), 2):
        acc += love[i]
        ids.append(acc)
    assert ai["id"] in ids
    sub = read_delta_list(con.execute("SELECT postings FROM substr_idx WHERE key = '究生'").fetchone()[0])
    assert entry(con, "研究生")["id"] in sub
    lese = con.execute("SELECT entry_id, alt FROM reading WHERE key = 'le se'").fetchall()
    assert (entry(con, "垃圾")["id"], 2) in lese


def test_redirect_and_hsk(built):
    con = db(built / "mandarin.db")
    e = entry(con, "一点儿")
    r = e["body"]["redirect"]
    assert r["kind"] == "erhua" and r["resolved"] and r["target"] == entry(con, "一点")["id"]
    assert not entry(con, "妖蛾子")["body"]["redirect"]["resolved"]
    dian = entry(con, "点")
    assert {"l": 1, "pos": ["量"], "x": 0} in dian["hsk"] and {"l": 2, "pos": ["动"], "x": 0} in dian["hsk"]
    assert entry(con, "骨董")["hsk_via"] and not entry(con, "骨董")["hsk"]
    row = con.execute("SELECT level, extra_levels, entry_id, also_entry_ids FROM hsk_row WHERE simp = '半'").fetchone()
    assert row[0] == 1 and json.loads(row[1]) == [{"l": 4, "pos": ["副"]}]
    hou = con.execute("SELECT entry_id, also_entry_ids FROM hsk_row WHERE simp = '后'").fetchone()
    trads = {con.execute("SELECT trad FROM entry WHERE id = ?", (i,)).fetchone()[0] for i in [hou[0]] + json.loads(hou[1])}
    assert trads == {"後", "后"}
    assert con.execute("SELECT count(*) FROM hsk_row r LEFT JOIN entry e ON e.id = r.entry_id WHERE e.id IS NULL").fetchone()[0] == 0
    patch = entry(con, "精彩纷呈")
    assert patch["body"]["blocks"][0]["kind"] == "hsk_patch" and patch["body"]["senses"][0]["src"] == "hsk_patch"


def test_unihan_and_radical_form(built):
    con = db(built / "mandarin.db")
    e = entry(con, "㐀")
    assert e["flags"] & 4 and e["body"]["blocks"][0]["kind"] == "unihan" and e["body"]["senses"][0]["src"] == "unihan"
    zu = {r[0] for r in con.execute("SELECT entry_id FROM headword WHERE key = '⻊' AND kind = 3")}
    assert entry(con, "足", "zu2")["id"] in zu


def test_cantonese(built):
    con = db(built / "cantonese.db")
    bank = con.execute("SELECT coalesce(reading_display, reading_canonical), reading_source, body FROM entry WHERE trad = '銀行'").fetchone()
    assert bank[0] == "ngan4 hong4" and bank[1] == 2
    body = unzjson(bank[2], body_dict(con))
    assert body["blocks"][0]["kind"] == "mandarin" and body["blocks"][0]["readings"][0]["d"] == "yín háng"
    hang = con.execute("SELECT reading_canonical, body FROM entry WHERE trad = '行' ORDER BY id").fetchall()
    assert len(hang) >= 5
    blocks = [next(b for b in unzjson(b_, body_dict(con))["blocks"] if b["kind"] == "mandarin")["readings"] for _r, b_ in hang]
    assert all([g["c"] for g in b] == [g["c"] for g in blocks[0]] for b in blocks)      # one shared Mandarin block
    none = con.execute("SELECT id, coalesce(reading_display, reading_canonical), reading_source FROM entry WHERE trad = '上升'").fetchone()
    assert none[1] == "" and none[2] == 5
    assert con.execute("SELECT count(*) FROM reading WHERE entry_id = ?", (none[0],)).fetchone()[0] == 0
    assert con.execute("SELECT flags & 2 FROM entry WHERE trad = '佢嘅'").fetchone()[0] == 0
    hsk = con.execute("SELECT coalesce(e.reading_display, e.reading_canonical) FROM hsk_row r JOIN entry e ON e.id = r.entry_id WHERE r.simp = '你好'").fetchone()
    assert hsk[0] == "nei5 hou2"
    meta = dict(con.execute("SELECT key, value FROM meta"))
    assert meta["romanization"] == "jyutping" and meta["tone_count"] == "6" and meta["has_hsk"] == "1"


def test_lm_file(built):
    lm = db(built / "mandarin-lm.db")
    main = db(built / "mandarin.db")
    assert dict(lm.execute("SELECT key, value FROM meta"))["dataset_version"] == \
        dict(main.execute("SELECT key, value FROM meta"))["dataset_version"]
    blob = lm.execute("SELECT postings FROM abbr_idx WHERE key = 'nh'").fetchone()[0]
    vals = read_varints(blob[:3])
    assert vals[0] >= 1 and vals[1] == entry(main, "你好")["id"]
    assert lm.execute("SELECT count(*) FROM bigram").fetchone()[0] > 0
    assert lm.execute("SELECT max(code) FROM lm_codebook WHERE kind = 0").fetchone()[0] <= 255
    widget = json.loads((OUT / "mandarin-widget.json").read_text("utf-8"))
    assert widget["rows"] and set(widget["rows"][0]) == {"id", "key", "levels", "simp", "trad", "reading", "gloss"}


def test_quality_pass_data(built):
    con = db(built / "mandarin.db")
    # entry.ulp carries the language model's unigram of the word (x 100): finite for every segmentable
    # entry, ranked words above the smoothed tail
    lo, hi = con.execute("SELECT min(ulp), max(ulp) FROM entry WHERE flags & 2").fetchone()
    assert -4000 < lo < hi < 0
    ranked = con.execute("SELECT min(ulp) FROM entry WHERE freq_rank < 1000000").fetchone()[0]
    assert con.execute("SELECT count(*) FROM entry WHERE freq_rank = 1000000 AND ulp > ?", (ranked,)).fetchone()[0] == 0
    assert con.execute("SELECT name FROM sqlite_master WHERE name IN ('word_freq', 'entry_freq')").fetchall() == []
    assert con.execute("SELECT typeof(natural_key), length(natural_key) FROM entry LIMIT 1").fetchone() == ("blob", 8)
    # reading rank: one rank-0 entry per simplified headword among the dictionary entries
    bad = con.execute("SELECT simp FROM entry WHERE (flags & 4) = 0 GROUP BY simp HAVING sum((flags >> 4) = 0) != 1").fetchall()
    assert not bad, bad[:5]
    ranks = dict(con.execute("SELECT reading_canonical, flags >> 4 FROM entry WHERE simp = '行' AND (flags & 4) = 0"))
    assert ranks["xing2"] == 0 and ranks["hang2"] > 0
    # english_word: common forms with counts, total in meta
    assert con.execute("SELECT freq FROM english_word WHERE word = 'hen'").fetchone()[0] > 0
    assert int(dict(con.execute("SELECT key, value FROM meta"))["english_word_total"]) > 10 ** 8
    # a multi-clause reading attaches its comma; tokens stay aligned with the headword
    d, canon, syl = con.execute("SELECT reading_display, reading_canonical, syllables FROM entry WHERE simp = '一朝被蛇咬，十年怕井绳'").fetchone()
    from szdict.roman import parse_pinyin
    d = d if d is not None else parse_pinyin(canon)[0]
    assert "yǎo, shí" in d and " ," not in d
    # proper nouns keep their stored display (the canonical reading is lowercase)
    assert con.execute("SELECT reading_display FROM entry WHERE simp = '中国'").fetchone()[0] == "Zhōng guó"
    assert syl is None and len(derive_reading_tokens(canon)[0]) == len(derive_reading_tokens(canon)[1]) == 11
    yue = db(built / "cantonese.db")
    assert yue.execute("SELECT flags >> 4 FROM entry WHERE trad = '佢' AND reading_canonical = 'keoi5'").fetchone()[0] == 0
