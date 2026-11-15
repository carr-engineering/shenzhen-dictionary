// Records which CJK code points the iOS system font cascade can draw, for tools/stage-app-fonts.py.
//
// Runs inside an iOS simulator (CoreText there is the device's, not the Mac's):
//
//   xcrun -sdk iphonesimulator swiftc -O -target arm64-apple-ios26.0-simulator tools/ios-font-probe.swift -o build/ios-font-probe
//   xcrun simctl spawn <udid> "$PWD/build/ios-font-probe" "$PWD/tools/ios-system-cjk.txt"
//
// For every code point of the blocks below it asks CoreText for the font the system would fall back
// to (CTFontCreateForString from the system UI font, as Skia's CoreText font manager does) and keeps
// the code point when that font is not LastResort and has a glyph for it. The output is hexadecimal
// ranges, one per line, preceded by a comment naming the OS version.
import CoreText
import Foundation
import UIKit

// The blocks of stage-web-fonts.py's CJK pattern.
let blocks: [ClosedRange<UInt32>] = [
    0x2E80...0x2FDF, 0x2FF0...0x2FFF, 0x3040...0x30FF, 0x3100...0x312F, 0x31A0...0x31EF,
    0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x3FFFF,
]
let base = CTFontCreateUIFontForLanguage(.system, 17, nil)!
var covered: [UInt32] = []
for block in blocks {
    for cp in block {
        guard let scalar = Unicode.Scalar(cp) else { continue }
        let text = String(Character(scalar))
        let string = text as CFString
        let font = CTFontCreateForString(base, string, CFRange(location: 0, length: CFStringGetLength(string)))
        if (CTFontCopyPostScriptName(font) as String) == "LastResort" { continue }
        var units = Array(text.utf16)
        var glyphs = [CGGlyph](repeating: 0, count: units.count)
        if CTFontGetGlyphsForCharacters(font, &units, &glyphs, units.count) { covered.append(cp) }
    }
}
var lines = ["# iOS \(UIDevice.current.systemVersion) system font cascade: \(covered.count) code points (tools/ios-font-probe.swift)"]
var start = covered.first, last = covered.first
for cp in covered.dropFirst() + [UInt32.max] {
    if let l = last, cp == l + 1 { last = cp; continue }
    if let s = start, let l = last { lines.append(s == l ? String(format: "%X", s) : String(format: "%X-%X", s, l)) }
    start = cp; last = cp
}
try! (lines.joined(separator: "\n") + "\n").write(toFile: CommandLine.arguments[1], atomically: true, encoding: .utf8)
print(lines[0])
