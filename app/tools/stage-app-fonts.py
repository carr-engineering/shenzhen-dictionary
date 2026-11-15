#!/usr/bin/env python3
"""Stages the iOS and Android interface fonts with the rare-character glyphs (GLB-008).

The system CJK fonts have no glyph for some 2,300–2,400 characters of the datasets and packs (rare
ideographs of CJK Extensions B–J, new ideographic description characters …), which then show as a
box. Fallback to a font the app brings along does not happen: on iOS, Skia's per-glyph fallback asks
only CoreText, whose cascade never offers app fonts (registered or given to Compose) and answers
with LastResort's "?" box; on Android the fallback chain is the system's. A glyph is found first in
the family the text is set in, so the glyphs go there.

For each platform this script writes, per weight of the interface font, Source Sans 3 (the
unmodified Compose resources, SIL OFL 1.1) with the characters that platform's system fonts lack
added from Plangothic P1 and P2 (SIL OFL 1.1); both licences' reserved names require a modified
version to be renamed, so the result is called "SZDict Sans":

  composeApp/build/ios/fonts/sans-<weight>.otf          bundled by :composeApp:stageIosAssets
  composeApp/build/android/fonts/sans-<weight>.otf      packaged by :composeApp:stageAndroidFonts

(regular, medium, semibold, bold). The apps use them in place of the Source Sans resources
(IosAppFonts.kt, AndroidAppFonts.kt). Only characters the system lacks are added, so every other
hanzi still comes from the system font. Nothing here is committed. Without the files the apps build
and run as before (rare characters drawn as boxes); the Gradle tasks warn.

What the system fonts draw is recorded, as code-point ranges, in
  tools/ios-system-cjk.txt      the iOS 26 cascade, by tools/ios-font-probe.swift in a simulator;
  tools/android-system-cjk.txt  AOSP's Noto Sans CJK (Android 16), by --record-android from
                                /system/fonts/NotoSansCJK-Regular.ttc pulled from an emulator.
Every dataset character outside a recording is added, so a character the recording does not know
is drawn from the added glyphs rather than missing. Android is recorded from Noto Sans CJK alone,
not from one device's whole fallback chain: phones ship other system fonts, and what Noto lacks
is the conservative set (it equals the Web rare tier).

  uv run --no-project --with fonttools --with cffsubr tools/stage-app-fonts.py [--platform ios|android|all] [--fonts DIR]
  uv run --no-project --with fonttools tools/stage-app-fonts.py --record-android NotoSansCJK-Regular.ttc

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

PLATFORMS = {
    # platform: (recording of what the system fonts draw, output directory)
    'ios': ('ios-system-cjk.txt', os.path.join('ios', 'fonts')),
    'android': ('android-system-cjk.txt', os.path.join('android', 'fonts')),
}
SOURCE_SANS = os.path.join(APP, 'composeApp', 'src', 'commonMain', 'composeResources', 'font')
WEIGHTS = ['regular', 'medium', 'semibold', 'bold']
FAMILY = 'SZDict Sans'


def system_coverage(platform):
    """The code points [platform]'s system fonts draw, from its recording."""
    covered = set()
    with open(os.path.join(APP, 'tools', PLATFORMS[platform][0]), encoding='utf-8') as f:
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


def record_android(ttc):
    """Writes tools/android-system-cjk.txt: the code points of the CJK blocks that [ttc] (every face) maps."""
    from fontTools.ttLib import TTCollection
    covered = set()
    collection = TTCollection(ttc, lazy=True)
    for face in collection.fonts:
        covered |= {cp for cp in face.getBestCmap() if web.CJK.match(chr(cp))}
    names = ', '.join(face['name'].getDebugName(6) for face in collection.fonts)
    path = os.path.join(APP, 'tools', PLATFORMS['android'][0])
    with open(path, 'w', encoding='utf-8') as f:
        f.write(f'# Android system CJK font ({os.path.basename(ttc)}: {names}): {len(covered)} code points '
                f'(tools/stage-app-fonts.py --record-android)\n')
        f.write(web.ranges(covered).replace(',', '\n') + '\n')
    print('wrote', path, len(covered), 'code points')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--platform', choices=['ios', 'android', 'all'], default='all')
    parser.add_argument('--fonts', default=os.path.join(APP, 'composeApp', 'build', 'web', 'font-cache'))
    parser.add_argument('--record-android', metavar='TTC')
    args = parser.parse_args()
    try:
        import fontTools  # noqa: F401
    except ImportError:
        sys.exit('fontTools is missing: pip install fonttools')
    if args.record_android:
        record_android(args.record_android)
        return

    os.makedirs(args.fonts, exist_ok=True)
    everything = set()
    for name in ('mandarin.db', 'cantonese.db', 'packs/sentences.db', 'packs/sentences-yue.db', 'packs/decomposition.db'):
        path = os.path.join(web.OUT, name)
        if os.path.isfile(path):
            everything |= web.all_characters(path)
    everything |= web.interface_characters()
    codepoints = {ord(ch) for ch in everything}
    for platform in (['ios', 'android'] if args.platform == 'all' else [args.platform]):
        out = os.path.join(APP, 'composeApp', 'build', PLATFORMS[platform][1])
        shutil.rmtree(out, ignore_errors=True)
        os.makedirs(out, exist_ok=True)
        missing = codepoints - system_coverage(platform)
        outlines, left = rare_outlines(missing, args.fonts)
        for weight in WEIGHTS:
            target = os.path.join(out, f'sans-{weight}.otf')
            count = merge(os.path.join(SOURCE_SANS, f'source_sans_3_{weight}.otf'), outlines, target, weight)
            print(f'{platform}/sans-{weight}.otf: Source Sans 3 + {count} characters, {os.path.getsize(target) / 1e6:.2f} MB')
        print(f'{platform}: dataset characters {len(codepoints)}, without a glyph in the system fonts {len(missing)}, '
              f'in no font {len(left)}' + (f' ({web.ranges(left)})' if left else ''))


if __name__ == '__main__':
    main()
