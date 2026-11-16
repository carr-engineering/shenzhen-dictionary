import math

import numpy as np

from szdict import lm_ngram as ng
from szdict.validate import _rv


def test_varints_match_scalar_encoding():
    v = np.array([0, 1, 127, 128, 300, 16383, 16384, 131071])
    out, off = ng._varints(v)
    for i, x in enumerate(v.tolist()):
        assert out[off[i]:off[i + 1]].tobytes() == ng._vi(x)


def test_codebook_uniform_grid():
    codes, book = ng.codebook(np.array([-40.0, -30.0, -10.0, -0.5]))
    assert len(book) == 256 and book[0] == -30.0 and abs(book[-1] + 0.5) < 1e-12
    assert codes.tolist()[:2] == [0, 0] and codes[-1] == 255


def test_segment_word():
    words = ["九", "点", "九点", "钟"]
    wid = {w: i for i, w in enumerate(words)}
    logp = [-5.0, -5.0, -8.0, -6.0]
    assert ng.segment_word("九点钟", wid, logp) == (2, 3)       # 九点 + 钟 beats 九 + 点 + 钟 (1 nat per word)
    assert ng.segment_word("九x", wid, logp) is None


def test_model_backoff_normalises():
    """Bigram rows plus backoff mass sum to one for a left word with a row."""
    V = 4
    uni = np.log(np.array([0.4, 0.3, 0.2, 0.1]))
    N1 = np.array([0.4, 0.3, 0.2, 0.1])
    M = ng.Masses(V, N1, np.array([0 * V + 1, 0 * V + 2]), np.array([0.2, 0.1]),
                  np.array([(0 * V + 1) * V + 3]), np.array([0.05]))
    m = ng.full_model(M, uni)
    a = np.zeros(V, np.int64)
    total = np.exp(m.bigram_lp(a, np.arange(V))).sum()
    assert math.isclose(total, 1.0, rel_tol=1e-9)
    t = np.exp(m.trigram_lp(a, np.ones(V, np.int64), np.arange(V))).sum()
    assert math.isclose(t, 1.0, rel_tol=1e-6)


def test_trigram_pages_round_trip(tmp_path):
    import sqlite3
    V = 5
    m = ng.Model(V, np.log(np.full(V, 0.2)))
    m.bk = np.array([0 * V + 1, 1 * V + 2, 2 * V + 3])
    m.blp = np.log(np.array([0.5, 0.5, 0.5]))
    ng.renorm_bigram(m)
    m.tk = np.array([(0 * V + 1) * V + 2, (0 * V + 1) * V + 4, (0 * V + 2) * V + 3, (1 * V + 2) * V + 3])
    m.tlp = np.log(np.array([0.4, 0.1, 0.3, 0.6]))
    ng.renorm_trigram(m)
    con = sqlite3.connect(tmp_path / "t.db", isolation_level=None)
    con.execute("CREATE TABLE lm_codebook(kind INTEGER, code INTEGER, value REAL)")
    info = ng.write_tables(con, m)
    assert info["trigrams"] == 4 and info["trigram_contexts"] == 3
    rows = con.execute("SELECT key, block FROM trigram ORDER BY key").fetchall()
    assert [k >> 17 for k, _b in rows] == [0, 1]          # one page per first word
    got = []
    for key, blob in rows:
        w1, w2, p = key >> 17, key & 0x1FFFF, 0
        while p < len(blob):
            d, p = _rv(blob, p)
            w2 += d
            p += 1
            n, p = _rv(blob, p)
            acc = 0
            for i in range(n):
                d, p = _rv(blob, p)
                acc = d if i == 0 else acc + d
                got.append((w1, w2, acc))
            p += n
    assert got == [(0, 1, 2), (0, 1, 4), (0, 2, 3), (1, 2, 3)]
    assert con.execute("SELECT count(*) FROM unigram2").fetchone()[0] == 1
