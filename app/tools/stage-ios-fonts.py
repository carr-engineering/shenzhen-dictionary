#!/usr/bin/env python3
"""Stages the iOS interface fonts with the rare-character glyphs (GLB-008).

iOS draws Chinese with the system font cascade (PingFang and its fallbacks), which has no glyph for
some 2,300 characters of the datasets and packs (rare ideographs of CJK Extensions B–J, new
ideographic description characters …): Compose shows them as LastResort's "?" box. Skia's
per-glyph fallback on iOS asks only CoreText, and CoreText never offers fonts the app brings along
(registered or given to Compose), so the glyphs must sit in the font the text is set in.

This script therefore writes, for each weight of the interface font, Source Sans 3 (the unmodified
Compose resources, SIL OFL 1.1) with those characters added from Plangothic P1 and P2 (SIL OFL
1.1); both licences' reserved names require a modified version to be renamed, so the result is
called "SZDict Sans":

  composeApp/build/ios/fonts/sans-<weight>.otf      regular, medium, semibold, bold

`:composeApp:stageIosAssets` bundles them as `assets/fonts/`, and the app uses them in place of the
Source Sans resources (IosAppFonts.kt). Only characters the system fonts lack are added, so every
other hanzi still comes from PingFang. Nothing here is committed. Without the files the app builds
and runs as before (rare characters drawn as boxes); the Gradle task warns.

The system cascade's coverage is not read on the Mac (its fonts differ): it is recorded in
tools/ios-system-cjk.txt by tools/ios-font-probe.swift, run in an iOS simulator (see that file).
Every dataset character outside it is added, so a character the recording does not know is drawn
from the added glyphs rather than missing.

  uv run --no-project --with fonttools --with cffsubr tools/stage-ios-fonts.py [--fonts DIR]

Without cffsubr the files are written unsubroutinized (1.6 MB instead of 1.0 MB each).

--fonts DIR  where the Plangothic sources are (downloaded there when missing, from the pinned URLs
             of stage-web-fonts.py, and checked against their SHA-256); default
             composeApp/build/web/font-cache, shared with the Web tool.
"""
import argparse
import importlib.util
import os
import shutil
import sys

APP = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_spec = importlib.util.spec_from_file_location('stage_web_fonts', os.path.join(APP, 'tools', 'stage-web-fonts.py'))
web = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(web)

COVERAGE = os.path.join(APP, 'tools', 'ios-system-cjk.txt')
SOURCE_SANS = os.path.join(APP, 'composeApp', 'src', 'commonMain', 'composeResources', 'font')
WEIGHTS = ['regular', 'medium', 'semibold', 'bold']
FAMILY = 'SZDict Sans'


def system_coverage():
    """The code points the iOS system font cascade draws, from the probe's recording."""
    covered = set()
    with open(COVERAGE, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            lo, _, hi = line.partition('-')
            covered.update(range(int(lo, 16), int(hi or lo, 16) + 1))
    return covered


def rare_outlines(codepoints, fonts_dir):
    """{code point: (advance, recording pen)} from Plangothic P1, then P2, and what neither has."""
    from fontTools.pens.recordingPen import DecomposingRecordingPen
    from fontTools.ttLib import TTFont
    outlines, left = {}, set(codepoints)
    for file in web.RARE_SOURCES:
        path = os.path.join(fonts_dir, file)
        web.fetch(web.PLANGOTHIC + file, path)
        font = TTFont(path, lazy=True)
        cmap, glyphs, hmtx = font.getBestCmap(), font.getGlyphSet(), font['hmtx']
        for cp in sorted(left & set(cmap)):
            pen = DecomposingRecordingPen(glyphs)
            glyphs[cmap[cp]].draw(pen)
            outlines[cp] = (hmtx[cmap[cp]][0], pen)
        left -= set(outlines)
        font.close()
    return outlines, left


def merge(source, outlines, target, weight):
    """Source Sans [source] with [outlines] added as CFF glyphs, renamed to FAMILY."""
    from fontTools.cffLib import CharStrings
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.pens.t2CharStringPen import T2CharStringPen
    from fontTools.ttLib import TTFont
    font = TTFont(source)
    cff = font['CFF '].cff
    top = cff.topDictIndex[0]
    private = top.Private
    charstrings: CharStrings = top.CharStrings
    order = font.getGlyphOrder()
    hmtx = font['hmtx']
    added = {}
    for cp, (advance, recording) in sorted(outlines.items()):
        name = f'u{cp:04X}' if cp > 0xFFFF else f'uni{cp:04X}'
        if name in charstrings:
            name += '.rare'
        pen = T2CharStringPen(advance - private.nominalWidthX, None)
        recording.replay(pen)
        charstring = pen.getCharString(private=private, globalSubrs=cff.GlobalSubrs)
        charstrings.charStringsIndex.append(charstring)
        charstrings.charStrings[name] = len(charstrings.charStringsIndex) - 1
        top.charset.append(name)
        bounds = BoundsPen(None)
        recording.replay(bounds)
        hmtx[name] = (advance, int(bounds.bounds[0]) if bounds.bounds else 0)
        order.append(name)
        added[cp] = name
    font.setGlyphOrder(order)
    font['maxp'].numGlyphs = len(order)
    for table in font['cmap'].tables:
        if table.isUnicode() and table.format == 12:
            table.cmap.update(added)
        elif table.isUnicode():
            table.cmap.update({cp: name for cp, name in added.items() if cp <= 0xFFFF})
    # A modified version may not carry the reserved names "Source" and "Plangothic" (OFL 1.1, condition 3).
    style = {'regular': 'Regular', 'medium': 'Medium', 'semibold': 'Semibold', 'bold': 'Bold'}[weight]
    postscript = f"{FAMILY.replace(' ', '')}-{style}"
    names = font['name']
    for record in list(names.names):
        if record.nameID in (1, 2, 3, 4, 6, 16, 17, 21, 22):
            names.removeNames(nameID=record.nameID)
    legacy_family = FAMILY if style in ('Regular', 'Bold') else f'{FAMILY} {style}'
    legacy_style = style if style in ('Regular', 'Bold') else 'Regular'
    for name_id, value in ((1, legacy_family), (2, legacy_style), (3, f'{postscript};szdict'), (4, f'{FAMILY} {style}'),
                           (6, postscript), (16, FAMILY), (17, style)):
        names.setName(value, name_id, 3, 1, 0x409)
    names.setName(
        'Source Sans 3 (© Adobe, Reserved Font Name "Source") with characters from Plangothic '
        '(© Fitzgerald P. Köeingsegg, Reserved Font Names "Plangothic", "遍黑"), modified for the Shenzhen Dictionary',
        10, 3, 1, 0x409)
    cff.fontNames = [postscript]
    top.FamilyName = FAMILY
    if hasattr(top, 'FullName'):
        top.FullName = f'{FAMILY} {style}'
    try:
        # The added outlines arrive without subroutines: cffsubr (Adobe's tx) shares their common parts, -40 %.
        import cffsubr
        cffsubr.subroutinize(font)
    except ImportError:
        pass
    font.save(target)
    font.close()
    return len(added)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--fonts', default=os.path.join(APP, 'composeApp', 'build', 'web', 'font-cache'))
    parser.add_argument('--out', default=os.path.join(APP, 'composeApp', 'build', 'ios', 'fonts'))
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
        path = os.path.join(web.OUT, name)
        if os.path.isfile(path):
            everything |= web.all_characters(path)
    everything |= web.interface_characters()
    codepoints = {ord(ch) for ch in everything}
    missing = codepoints - system_coverage()
    outlines, left = rare_outlines(missing, args.fonts)
    for weight in WEIGHTS:
        target = os.path.join(args.out, f'sans-{weight}.otf')
        count = merge(os.path.join(SOURCE_SANS, f'source_sans_3_{weight}.otf'), outlines, target, weight)
        print(f'sans-{weight}.otf: Source Sans 3 + {count} characters, {os.path.getsize(target) / 1e6:.2f} MB')
    print(f'dataset characters: {len(codepoints)}, without a glyph in the iOS system fonts: {len(missing)}, '
          f'in no font: {len(left)}' + (f' ({web.ranges(left)})' if left else ''))


if __name__ == '__main__':
    main()
