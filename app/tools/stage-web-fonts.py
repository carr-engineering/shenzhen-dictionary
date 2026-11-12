#!/usr/bin/env python3
"""Stages the Web CJK font as frequency subsets (ARCH-005, GLB-008).

Compose draws text itself, so the Web build fetches Noto Sans CJK after start-up. The complete font
is 16 MB per glyph variant; this script cuts each variant (SC, TC, HK) into

  <v>-core.otf   the most frequent characters of the dataset's headwords, with punctuation and the
                 accented Latin letters of Pinyin — what the first screens need;
  <v>-mid.otf    the next few thousand headword characters;
  <v>-tail.otf   every other character the datasets and packs contain;

and writes fonts/index.json, which the app reads to fetch them in that order (WebFont.kt). The
complete font still comes from the font host when Reading or OCR shows text from outside the
dictionary. Nothing here is committed: the output goes to composeApp/build/web/static/cjk/fonts,
which the Web distribution includes.

  python3 tools/stage-web-fonts.py [--core 2000] [--mid 4500] [--fonts DIR] [--full]

--fonts DIR  where NotoSansCJK{sc,tc,hk}-Regular.otf are (downloaded there when missing);
             default composeApp/build/web/font-cache.
--full       also stage the complete fonts, for a deployment or a development machine that must
             not ask the font host.

Needs fontTools (`pip install fonttools`).
"""
import argparse
import json
import os
import re
import shutil
import sqlite3
import sys
import urllib.request
import zlib

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(APP, '..', 'pipeline', 'out')
HOST = 'https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@Sans2.004/Sans/OTF/'
VARIANTS = {
    # variant: (font file, host directory, dataset, headword column whose frequency orders the core)
    'sc': ('NotoSansCJKsc-Regular.otf', 'SimplifiedChinese', 'mandarin', 'simp'),
    'tc': ('NotoSansCJKtc-Regular.otf', 'TraditionalChinese', 'mandarin', 'trad'),
    'hk': ('NotoSansCJKhk-Regular.otf', 'TraditionalChineseHK', 'cantonese', 'trad'),
}
# Always in the core: Latin with the tone marks of Pinyin, general and CJK punctuation, full-width forms.
BASICS = [(0x00A0, 0x024F), (0x02B0, 0x02FF), (0x0300, 0x036F), (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x2190, 0x21FF),
          (0x3000, 0x303F), (0xFE30, 0xFE4F), (0xFF00, 0xFFEF)]
CJK = re.compile('[⺀-⿟⿰-⿿぀-ヿ㄀-ㄯㆠ-㇯㐀-䶿一-鿿豈-﫿\U00020000-\U0003ffff]')


def headword_order(db, column):
    """Characters of the headwords in [column], most frequent entry first."""
    order, seen = [], set()
    for (text,) in db.execute(f'SELECT {column} FROM entry ORDER BY freq_rank, id'):
        for ch in text:
            if ch not in seen and CJK.match(ch):
                seen.add(ch)
                order.append(ch)
    return order


def all_characters(path):
    """Every CJK character in the text columns of a dataset or pack, and in the compressed entry bodies."""
    found = set()
    db = sqlite3.connect(path)
    tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")]
    preset = None
    if 'body_dict' in tables:
        row = db.execute('SELECT dict FROM body_dict ORDER BY id LIMIT 1').fetchone()
        preset = row[0] if row else None
    for table in tables:
        if table.startswith('sqlite_'):
            continue
        for row in db.execute(f'SELECT * FROM "{table}"'):
            for value in row:
                if isinstance(value, str):
                    found.update(CJK.findall(value))
                elif isinstance(value, bytes) and table == 'entry' and len(value) > 2:
                    try:
                        inflater = zlib.decompressobj(-15, zdict=preset) if preset else zlib.decompressobj(-15)
                        found.update(CJK.findall(inflater.decompress(value).decode('utf-8', 'ignore')))
                    except zlib.error:
                        pass
                elif isinstance(value, bytes) and 2 < len(value) < 4096:
                    found.update(CJK.findall(value.decode('utf-8', 'ignore')))
    db.close()
    return found


def subset(font, codepoints, target):
    from fontTools import subset as ft
    options = ft.Options()
    options.notdef_outline = True
    options.name_IDs = [1, 2, 3, 4, 6]
    options.drop_tables += ['DSIG', 'VORG', 'vhea', 'vmtx', 'BASE']
    loaded = ft.load_font(font, options)
    subsetter = ft.Subsetter(options)
    subsetter.populate(unicodes=sorted(codepoints))
    subsetter.subset(loaded)
    ft.save_font(loaded, target, options)
    kept = len(set(loaded.getBestCmap()) & set(codepoints))
    loaded.close()
    return kept


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--core', type=int, default=2000, help='headword characters in the first subset')
    parser.add_argument('--mid', type=int, default=4500, help='headword characters in the second subset')
    parser.add_argument('--fonts', default=os.path.join(APP, 'composeApp', 'build', 'web', 'font-cache'))
    parser.add_argument('--out', default=os.path.join(APP, 'composeApp', 'build', 'web', 'static', 'cjk', 'fonts'))
    parser.add_argument('--full', action='store_true')
    args = parser.parse_args()
    try:
        import fontTools  # noqa: F401
    except ImportError:
        sys.exit('fontTools is missing: pip install fonttools')

    os.makedirs(args.fonts, exist_ok=True)
    shutil.rmtree(args.out, ignore_errors=True)
    os.makedirs(args.out, exist_ok=True)
    everything = set()
    for name in ('mandarin.db', 'cantonese.db', 'packs/sentences.db', 'packs/sentences-yue.db', 'packs/decomposition.db'):
        path = os.path.join(OUT, name)
        if os.path.isfile(path):
            everything |= all_characters(path)
    basics = {cp for lo, hi in BASICS for cp in range(lo, hi + 1)}
    index = {}
    for variant, (file, directory, dataset, column) in VARIANTS.items():
        font = os.path.join(args.fonts, file)
        if not os.path.isfile(font):
            print('downloading', file)
            urllib.request.urlretrieve(HOST + directory + '/' + file, font + '.part')
            os.replace(font + '.part', font)
        db = sqlite3.connect(os.path.join(OUT, dataset + '.db'))
        order = headword_order(db, column)
        # The other script's form of the entries that reach the core is shown beside them.
        head = set(order[:args.core])
        companions = set()
        for primary, secondary in db.execute(f"SELECT {column}, {'trad' if column == 'simp' else 'simp'} FROM entry ORDER BY freq_rank, id"):
            if all(ch in head or not CJK.match(ch) for ch in primary):
                companions.update(ch for ch in secondary if CJK.match(ch))
        db.close()
        core = basics | {ord(ch) for ch in head | companions}
        mid = {ord(ch) for ch in order[args.core:args.core + args.mid]} - core
        tail = {ord(ch) for ch in everything} - core - mid
        entry = {'tiers': []}
        for tier, codepoints in (('core', core), ('mid', mid), ('tail', tail)):
            name = f'{variant}-{tier}.otf'
            kept = subset(font, codepoints, os.path.join(args.out, name))
            entry['tiers'].append(name)
            print(f'{name}: {kept} of {len(codepoints)} characters, {os.path.getsize(os.path.join(args.out, name)) / 1e6:.2f} MB')
        if args.full:
            shutil.copyfile(font, os.path.join(args.out, file))
            entry['full'] = file
        index[variant] = entry
    with open(os.path.join(args.out, 'index.json'), 'w') as f:
        json.dump(index, f)
    print('wrote', os.path.join(args.out, 'index.json'))


if __name__ == '__main__':
    main()
