"""Spec examples for normalisation, tokenisation, readings and sense parsing (ARCH-154, PIPE-141)."""
import json
from pathlib import Path

import pytest

from szdict.enc import delta_list, read_delta_list, read_varints, unzjson, varint, zjson
from szdict.norm import display_key, en_tokens, fold, is_hanzi, matching_key
from szdict.roman import (mark_syllable, marked_to_numbered, parse_jyutping, parse_pinyin, reading_key,
                          respace_numbered, sort_reading)
from szdict.senses import parse_gloss, parse_segments

REPO = Path(__file__).resolve().parents[2]


# --- SRCH-010 ------------------------------------------------------------------------------
@pytest.mark.parametrize("raw,key", [
    ("  NI3   HAO3!! ", "ni3 hao3"),
    ("Ｔ恤", "t恤"),
    ("xi’an", "xi'an"),
    ("马丁・路德•金", "马丁·路德·金"),
    ("。，！？", ""),
    ("ni,hao", "ni hao"),
    ("graduate-student", "graduate student"),
    ("你好！", "你好"),
    ("🙂 hello", "hello"),
    ("ｎｉ３", "ni3"),
    ("nu:3", "nü3"),
    ("nǐ", "nǐ"),
    ("⼈", "人"),
])
def test_matching_key(raw, key):
    assert matching_key(raw) == key
    assert matching_key(key) == key          # idempotent


def test_fold_and_display():
    assert fold("Ｔ恤") == "T恤"
    assert fold("马丁・路德•金") == "马丁·路德·金" == fold("马丁‧路德･金")
    assert display_key("  你好　 世界 ") == "你好 世界"
    assert display_key("Ｔ恤") == "Ｔ恤"


def test_hanzi_set():
    assert all(is_hanzi(c) for c in "你〇㐀𠀀𰻝")
    assert not any(is_hanzi(c) for c in "A。·⼈⻊のㄅ")


# --- SRCH-013 ------------------------------------------------------------------------------
def test_english_tokens():
    assert en_tokens("Graduate students") == ["graduate", "student"]
    assert en_tokens("glass") == ["glass"]            # ends in ss
    assert en_tokens("bus") == ["bus"]                # length 3
    assert en_tokens("T-shirt") == ["t", "shirt"]
    assert en_tokens("(of sb/sth) 3D-print") == ["of", "sb", "sth", "3d", "print"]
    assert en_tokens("") == []


# --- GLB-015, PIPE-021, PIPE-022 -------------------------------------------------------------
@pytest.mark.parametrize("syl,tone,marked", [
    ("hao", 3, "hǎo"), ("mei", 2, "méi"), ("xie", 3, "xiě"), ("lüe", 4, "lüè"), ("dou", 1, "dōu"),
    ("gui", 4, "guì"), ("liu", 2, "liú"), ("duo", 1, "duō"), ("jiong", 3, "jiǒng"), ("nü", 3, "nǚ"),
    ("ma", 5, "ma"), ("Ou", 1, "Ōu"),
])
def test_tone_marks(syl, tone, marked):
    assert mark_syllable(syl, tone) == marked


def test_syllabic_nasals():
    import unicodedata
    assert unicodedata.normalize("NFD", mark_syllable("m", 2)) == "ḿ"
    assert unicodedata.normalize("NFD", mark_syllable("n", 3)) == "ň"
    assert unicodedata.normalize("NFD", mark_syllable("ng", 2)) == "ńg"


def test_pinyin_reading():
    d, c, syl, tones, proper, full, cased = parse_pinyin("ni3 hao3")
    assert (d, c, syl, tones, proper, full) == ("nǐ hǎo", "ni3 hao3", ("ni", "hao"), (3, 3), False, True)
    d, c, syl, tones, proper, full, cased = parse_pinyin("lu:e4")
    assert (d, c, syl) == ("lüè", "lüe4", ("lüe",))
    assert parse_pinyin("Wang2")[4] is True and parse_pinyin("Wang2")[1] == "wang2" and parse_pinyin("Wang2")[6] == "Wang2"


def test_toneless_reading_keeps_literal_digits():      # PIPE-022: 11區 [11 Qu1]
    d, c, syl, tones, proper, full, _ = parse_pinyin("11 Qu1")
    assert syl == ("11", "qu") and tones == (0, 1) and d == "11 Qū" and reading_key(syl) == "11 qu"
    assert parse_pinyin("T xu4")[2] == ("t", "xu") and parse_pinyin("T xu4")[0] == "T xù"
    assert parse_pinyin("san1 C")[2] == ("san", "c")


def test_punctuation_tokens():
    d, c, syl, tones, proper, full, _ = parse_pinyin("Ma3 ding1 · Lu4 de2 · Jin1")
    assert len(syl) == 7 and reading_key(syl) == "ma ding lu de jin" and full


def test_marked_and_unspaced():
    assert marked_to_numbered("tóngshí") == "tong2 shi2"
    assert marked_to_numbered("gè") == "ge4"
    assert marked_to_numbered("lüè") == "lüe4"
    assert marked_to_numbered("zzz") is None
    assert respace_numbered("lian2lei3") == "lian2 lei3"
    assert respace_numbered("Ling2 Meng2chu1") == "Ling2 Meng2 chu1"
    assert sort_reading(" Lü4  r5 ") == "lv4 r5"


def test_jyutping():
    d, c, syl, tones, full = parse_jyutping("nei5 hou2")
    assert (d, c, syl, tones, full) == ("nei5 hou2", "nei5 hou2", ("nei", "hou"), (5, 2), True)
    assert parse_jyutping("ngo5 dei6")[2] == ("ngo", "dei")


# --- sense parsing (PIPE-025, PIPE-027, PIPE-079) ---------------------------------------------
def one(gloss):
    return parse_gloss(gloss, "cedict", "si")


def test_structural_notes():
    s, n = one("see 尖山區|尖山区[Jian1 shan1 Qu1]")
    assert not s and n[0]["kind"] == "see" and n[0]["refs"][0]["tr"] == "尖山區" and n[0]["refs"][0]["si"] == "尖山区"
    assert n[0]["refs"][0]["c"] == "jian1 shan1 qu1" and n[0]["refs"][0]["d"] == "Jiān shān Qū"
    s, n = one("CL:條|条[tiao2],隻|只[zhi1]")
    assert n[0]["kind"] == "classifier" and [r["si"] for r in n[0]["refs"]] == ["条", "只"]
    for g, kind in [("variant of 蟹[xie4]", "variant_of"), ("old variant of 留[liu2]", "old_variant_of"),
                    ("erhua variant of 一點|一点[yi1 dian3]", "erhua_variant_of"),
                    ("abbr. for 電子郵件|电子邮件[dian4 zi3 you2 jian4]", "abbr_for"),
                    ("also written 敘文|叙文[xu4 wen2]", "also_written"), ("used in 茳芏[jiang1 du4]", "used_in"),
                    ("see also 盜版|盗版[dao4 ban3]", "see_also")]:
        s, n = one(g)
        assert not s and n[0]["kind"] == kind, g


def test_alternative_reading_notes():
    s, n = one("also pr. [lian2lei3] or [lian2lei4]")
    assert n[0]["kind"] == "also_pr" and n[0]["_readings"] == ["lian2 lei3", "lian2 lei4"]
    s, n = one("used in 逮捕[dai4 bu3] (Taiwan pr. [dai3])")
    assert [x["kind"] for x in n] == ["used_in", "taiwan_pr"] and n[1]["reading"] == "dǎi"


def test_tags():
    s, n = one("(Tw) (coll.) to chat")
    assert s[0]["t"] == "to chat" and s[0]["tags"] == ["taiwan", "colloquial"]
    s, n = one("(of sb/sth) to fall")                 # not allow-listed: stays inline
    assert s[0]["t"] == "(of sb/sth) to fall" and s[0]["tags"] == []
    s, n = one("surname Niu")
    assert s[0]["t"] == "Niu" and s[0]["tags"] == ["surname"] and s[0]["tok"] == ["surname", "niu"]
    s, n = one("classifier for pairs or sets of things")
    assert s[0]["tags"] == ["measure_word"]


def test_embedded_variant_and_semantic_gloss():
    s, n = one("sardine (variant of 鰮|鳁[wen1])")
    assert s[0].get("evr") is True and not n
    s, n = one("variant of 鰮|鳁, sardine")
    assert s and not n and s[0]["seg"][1]["k"] == "ref"


def test_segments_and_romanisation():
    segs = parse_segments("Jia Baoyu, in love with 林黛玉 but not 查爾斯·狄更斯|查尔斯·狄更斯[Cha2 er3 si1 · Di2 geng1 si1]")
    refs = [x for x in segs if x["k"] == "ref"]
    assert refs[0]["tr"] == refs[0]["si"] == "林黛玉" and "c" not in refs[0]
    assert refs[1]["si"] == "查尔斯·狄更斯"
    segs = parse_segments("(loanword from Cantonese) dim sum [dim2 sam1]")
    assert segs[-1] == {"k": "r", "d": "dim2 sam1", "rom": "jyutping"}
    segs = parse_segments("pronounced [ha1] in Taiwan")
    assert segs[1] == {"k": "r", "d": "hā", "rom": "pinyin"}
    segs = parse_segments("X-ray, also written X光[X guang1]")
    assert segs[-1]["k"] == "ref" and segs[-1]["si"] == "X光" and segs[-1]["d"] == "X guāng"


# --- encodings --------------------------------------------------------------------------------
def test_encodings():
    assert varint(0) == b"\x00" and varint(127) == b"\x7f" and varint(128) == b"\x80\x01" and varint(300) == b"\xac\x02"
    ids = [3, 7, 8, 300, 70000]
    assert read_delta_list(delta_list(ids)) == ids
    assert read_varints(varint(5) + varint(130)) == [5, 130]
    obj = {"senses": [{"t": "你好"}]}
    assert unzjson(zjson(obj)) == obj


def test_vectors_file_is_current():
    import make_vectors
    stored = json.loads((REPO / "contract" / "normalisation-vectors.json").read_text("utf-8"))
    assert stored == json.loads(json.dumps(make_vectors.build(), ensure_ascii=False))


# --- quality pass: display readings, Pleco references, CC-Canto simplified forms, reading ranks ---------
def test_display_reading_attaches_commas():
    d, c, syl, tones = parse_pinyin("yi1 bu4 zuo4 , er4 bu4 xiu1")[:4]
    assert d == "yī bù zuò, èr bù xiū"
    assert c == "yi1 bu4 zuo4 , er4 bu4 xiu1" and len(syl) == len(tones) == 7      # one token per source token
    assert parse_pinyin("Ma3 ding1 · Lu4 de2")[0] == "Mǎ dīng · Lù dé"            # a middle dot keeps its spaces


def test_pleco_reference_forms():
    from szdict.cantonese import PLECO_RE
    cases = {
        "Mandarin equivalent: 他tā [他]": ("他", "tā"),
        "abbr. for Anhui Province 安徽省 Ānhuī Shěng [安徽省]": ("安徽省", "Ānhuī Shěng"),
        "same as 渤海 Bó Hǎi [渤海], Bohai Sea": ("渤海", "Bó Hǎi"),
        "the first emperor 秦始皇 Qín Shǐhuáng [秦始皇]": ("秦始皇", "Qín Shǐhuáng"),
        "abbr. for 陕西Shǎnxī [陕西]": ("陕西", "Shǎnxī"),
    }
    for text, (hz, pin) in cases.items():
        m = PLECO_RE.search(text)
        assert m and (m.group(1), m.group(2)) == (hz, pin), text
    assert PLECO_RE.search("see 楞迦 Sri Lanka") is None
    assert marked_to_numbered("ānhuī shěng", 3) == "an1 hui1 sheng3"


def test_cccanto_simplified_forms():
    from szdict.cantonese import SimplifiedFixer
    cedict = [{"trad": t, "simp": s} for t, s in (
        ("佢", "佢"), ("阿姨", "阿姨"), ("濕", "湿"), ("潮濕", "潮湿"), ("囉", "啰"), ("乾", "干"), ("乾", "乾"), ("個", "个"))]
    fx = SimplifiedFixer(cedict)
    assert fx.fix("佢哋", "渠哋") == "佢哋"            # A: the character itself is the simplified form
    assert fx.fix("阿乜人", "啊乜人") == "阿乜人"
    assert fx.fix("口垃濕", "口垃濕") == "口垃湿"       # B: left unsimplified
    assert fx.fix("個囉", "个罗") == "个啰"            # C: unattested replacement
    assert fx.fix("乾手", "干手") == "干手" and fx.fix("乾手", "乾手") == "乾手"   # both attested: kept
    assert fx.fix("嗰排", "嗰排") == "嗰排"            # Cantonese character without an attested simplification
    assert fx.report()["per_rule"] == {"A": 2, "B": 1, "C": 1}


def test_reading_rank_order():
    from szdict.core import Dataset, assign_reading_ranks, flags_of
    ds = Dataset("mandarin")
    base = dict(display="", cased="", syllables=(), tones=(), rsrc=0)
    dan = ds.add(trad="石", simp="石", canonical="dan4", senses=[{}, {}], **base)
    shi = ds.add(trad="石", simp="石", canonical="shi2", senses=[{}], **base)
    name = ds.add(trad="石", simp="石", canonical="shi2", senses=[{}], proper=True, **base)
    hang = ds.add(trad="行", simp="行", canonical="hang2", senses=[{}], hsk=[{"l": 5}], **base)
    xing = ds.add(trad="行", simp="行", canonical="xing2", senses=[{}], hsk=[{"l": 3}], **base)
    er = ds.add(trad="兒", simp="儿", canonical="er2", senses=[{}], **base)
    r = ds.add(trad="兒", simp="儿", canonical="r5", senses=[{}, {}, {}], **base)
    assign_reading_ranks(ds, {"石": {"kHanyuPinlu": "shí(1006)", "kMandarin": "shí"},
                              "行": {"kHanyuPinlu": "xíng(2943) háng(218)"}, "儿": {"kHanyuPinlu": "r(4527) ér(1000)"}})
    assert [e["rrank"] for e in (shi, name, dan)] == [0, 1, 2]       # Unihan order, then common before proper
    assert (xing["rrank"], hang["rrank"]) == (0, 1)                  # lower HSK level first
    assert (er["rrank"], r["rrank"]) == (0, 1)                       # the non-syllabic suffix never leads
    assert flags_of(dan) >> 4 == 2 and flags_of(shi) >> 4 == 0
