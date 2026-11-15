#!/usr/bin/env python3
"""Stages the Web CJK font as frequency subsets (ARCH-005, GLB-008).

Compose draws text itself, so the Web build fetches Noto Sans CJK after start-up. The complete font
is 16 MB per glyph variant; this script cuts each variant (SC, TC, HK) into

  <v>-core.otf   the most frequent characters of the dataset's headwords, every character of the
                 interface text (the Kotlin sources of the shared and Web UI: labels, translations,
                 the privacy policy), with punctuation and the accented Latin letters of Pinyin —
                 what the first screens need;
  <v>-mid.otf    the next few thousand headword characters;
  <v>-tail.otf   every other character the datasets and packs contain;

and, for every glyph variant alike,

  rare-<n>.ttf   the characters of the datasets and packs that Noto Sans CJK has no glyph for (rare
                 characters of CJK Extensions B–J, new ideographic description characters …), cut
                 from Plangothic P1 and P2 (SIL OFL 1.1; renamed, as the licence's reserved font name
                 requires of a modified version). The app fetches one only when a result about to be
                 shown contains one of its characters;

and writes fonts/index.json, which the app reads to fetch them (WebFont.kt) and to verify each
file's SHA-256. The complete font still comes from the font host when Reading or OCR shows text
from outside the dictionary. Nothing here is committed: the output goes to
composeApp/build/web/static/cjk/fonts, which the Web distribution includes.

  uv run --no-project --with fonttools tools/stage-web-fonts.py [--core 2000] [--mid 4500] [--fonts DIR] [--full]

--fonts DIR  where the source fonts are (downloaded there when missing, from the pinned URLs below,
             and checked against their SHA-256); default composeApp/build/web/font-cache.
--full       also stage the complete fonts, for a deployment or a development machine that must
             not ask the font host.

Needs fontTools (`pip install fonttools`).
"""
import argparse
import hashlib
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
# Rare characters: Plangothic V2.9.5795 (github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project,
# SIL OFL 1.1, reserved names "Plangothic" and "遍黑"), P1 first, P2 for the rest.
PLANGOTHIC = 'https://github.com/Fitzgerald-Porthmouth-Koenigsegg/Plangothic_Project/releases/download/V2.9.5795/'
RARE_SOURCES = ['PlangothicP1-Regular.ttf', 'PlangothicP2-Regular.ttf']
RARE_FAMILY = 'SZDict Rare Han'
# SHA-256 of every downloaded font: a changed file stops the script.
PINNED = {
    'NotoSansCJKsc-Regular.otf': '2c76254f6fc379fddfce0a7e84fb5385bb135d3e399294f6eeb6680d0365b74b',
    'NotoSansCJKtc-Regular.otf': 'dce08bd4fd91aa8aa76ed8fea4b694c2dfb8550f67871e326843212ddbeb88b4',
    'NotoSansCJKhk-Regular.otf': '97c937514d645eae90415d30ba025e08a94d5bdffdc627404864f90aa0c7d83b',
    'PlangothicP1-Regular.ttf': '550b5d0775b15405946b18f4843df439a51e69508d7e6778d94c1f7a53dc5ad6',
    'PlangothicP2-Regular.ttf': '681933370adfe0fc7253f77735275a82fea09fe4f8adba907bdeb46c110daf8f',
}
# Always in the core: Latin with the tone marks of Pinyin, general and CJK punctuation, full-width forms.
BASICS = [(0x00A0, 0x024F), (0x02B0, 0x02FF), (0x0300, 0x036F), (0x1E00, 0x1EFF), (0x2000, 0x206F), (0x2190, 0x21FF),
          (0x3000, 0x303F), (0xFE30, 0xFE4F), (0xFF00, 0xFFEF)]
CJK = re.compile('[⺀-⿟⿰-⿿぀-ヿ㄀-ㄯㆠ-㇯㐀-䶿一-鿿豈-﫿\U00020000-\U0003ffff]')


def interface_characters():
    """Every CJK character of the app's own text: the Kotlin sources of the shared and Web UI."""
    found = set()
    for source in ('commonMain', 'wasmJsMain'):
        for root, _, files in os.walk(os.path.join(APP, 'composeApp', 'src', source, 'kotlin')):
            for name in files:
                if name.endswith('.kt'):
                    with open(os.path.join(root, name), encoding='utf-8') as f:
                        found.update(CJK.findall(f.read()))
    return found


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


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def fetch(url, path):
    """Downloads [url] to [path] unless it is there, and checks the file against its pinned SHA-256."""
    name = os.path.basename(path)
    if not os.path.isfile(path):
        print('downloading', name)
        urllib.request.urlretrieve(url, path + '.part')
        os.replace(path + '.part', path)
    if sha256(path) != PINNED[name]:
        sys.exit(f'{path}: SHA-256 differs from the pinned {PINNED[name]}; delete it to download again')


def subset(font, codepoints, target, family=None):
    from fontTools import subset as ft
    options = ft.Options()
    options.notdef_outline = True
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    options.drop_tables += ['DSIG', 'VORG', 'vhea', 'vmtx', 'BASE']
    loaded = ft.load_font(font, options)
    subsetter = ft.Subsetter(options)
    subsetter.populate(unicodes=sorted(codepoints))
    subsetter.subset(loaded)
    if family:
        # A modified version under a reserved font name must be renamed (OFL 1.1, condition 3).
        names = loaded['name']
        for record in list(names.names):
            if record.nameID in (16, 17, 21, 22):
                names.removeNames(nameID=record.nameID)
        for name_id, value in ((1, family), (3, family.replace(' ', '') + '-Regular'), (4, family), (6, family.replace(' ', '') + '-Regular')):
            names.setName(value, name_id, 3, 1, 0x409)
            names.setName(value, name_id, 1, 0, 0)
    ft.save_font(loaded, target, options)
    kept = len(set(loaded.getBestCmap()) & set(codepoints))
    loaded.close()
    return kept


def ranges(codepoints):
    """Code points as hexadecimal ranges: `20000-20003,2A6D6`."""
    out, run = [], []
    for cp in sorted(codepoints):
        if run and cp == run[-1] + 1:
            run.append(cp)
            continue
        if run:
            out.append(f'{run[0]:X}' if len(run) == 1 else f'{run[0]:X}-{run[-1]:X}')
        run = [cp]
    if run:
        out.append(f'{run[0]:X}' if len(run) == 1 else f'{run[0]:X}-{run[-1]:X}')
    return ','.join(out)


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
    # Interface text (Settings labels such as 粵語 / 繁體, translated chrome) is drawn before any
    # result: it belongs to the first tier of every variant.
    basics |= {ord(ch) for ch in interface_characters()}
    index = {}
    noto = set()
    for variant, (file, directory, dataset, column) in VARIANTS.items():
        font = os.path.join(args.fonts, file)
        fetch(HOST + directory + '/' + file, font)
        from fontTools.ttLib import TTFont
        noto |= set(TTFont(font, lazy=True).getBestCmap())
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

    # The rare tier: what the datasets contain and no Noto Sans CJK variant has.
    from fontTools.ttLib import TTFont
    rare = {ord(ch) for ch in everything} - noto
    left = set(rare)
    index['rare'] = []
    for number, file in enumerate(RARE_SOURCES, start=1):
        font = os.path.join(args.fonts, file)
        fetch(PLANGOTHIC + file, font)
        codepoints = left & set(TTFont(font, lazy=True).getBestCmap())
        if not codepoints:
            continue
        name = f'rare-{number}.ttf'
        kept = subset(font, codepoints, os.path.join(args.out, name), family=f'{RARE_FAMILY} {number}')
        left -= codepoints
        index['rare'].append({'file': name, 'ranges': ranges(codepoints)})
        print(f'{name}: {kept} of {len(rare)} rare characters, {os.path.getsize(os.path.join(args.out, name)) / 1e6:.2f} MB')
    print(f'rare characters: {len(rare)}, without a glyph in any font: {len(left)}' + (f' ({ranges(left)})' if left else ''))

    # Every file's SHA-256: the app verifies what it downloads (WebFont.kt).
    index['sha256'] = {name: sha256(os.path.join(args.out, name)) for name in sorted(os.listdir(args.out)) if name != 'index.json'}
    with open(os.path.join(args.out, 'index.json'), 'w') as f:
        json.dump(index, f)
    print('wrote', os.path.join(args.out, 'index.json'))


if __name__ == '__main__':
    main()
