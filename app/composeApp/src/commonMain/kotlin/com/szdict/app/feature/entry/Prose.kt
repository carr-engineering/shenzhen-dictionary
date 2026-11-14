package com.szdict.app.feature.entry

import com.szdict.core.api.AltReading
import com.szdict.core.api.Label
import com.szdict.core.api.Note
import com.szdict.core.api.Segment
import com.szdict.core.api.UiLang

/** What a range of a rendered definition is (ENT-051…053). */
internal enum class ProseSpanKind {
    /** Bold sense number. */
    NUMBER,

    /** Tap target covering one reference form (ENT-055): accent + underline. */
    LINK,

    /** A reading with its brackets: follows pronunciation visibility (ENT-052, GLB-120). */
    READING,

    /** Inline tag placeholder (ENT-058); [ProseSpan.label] is drawn in its place. */
    TAG,
}

internal data class ProseSpan(
    val kind: ProseSpanKind,
    val start: Int,
    val end: Int,
    val ref: Segment.Ref? = null,
    val label: String? = null,
)

/** Platform-free rendering plan of one paragraph: text plus typed UTF-16 ranges. */
internal data class Prose(val text: String, val spans: List<ProseSpan>) {
    val links: List<ProseSpan> get() = spans.filter { it.kind == ProseSpanKind.LINK }

    /** The text with hidden readings removed, for accessibility labels (GLB-122). */
    fun textWithoutReadings(): String {
        val sb = StringBuilder()
        var at = 0
        for (span in spans.filter { it.kind == ProseSpanKind.READING }.sortedBy { it.start }) {
            if (span.start > at) sb.append(text, at, span.start)
            at = maxOf(at, span.end)
        }
        if (at < text.length) sb.append(text, at, text.length)
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }
}

internal const val TAG_PLACEHOLDER = '￼'

internal class ProseBuilder {
    private val sb = StringBuilder()
    private val spans = ArrayList<ProseSpan>()

    val length: Int get() = sb.length

    fun text(value: String): ProseBuilder {
        sb.append(value)
        return this
    }

    fun span(kind: ProseSpanKind, value: String, ref: Segment.Ref? = null, label: String? = null): ProseBuilder {
        if (value.isEmpty()) return this
        val start = sb.length
        sb.append(value)
        spans += ProseSpan(kind, start, sb.length, ref, label)
        return this
    }

    fun segments(segments: List<Segment>): ProseBuilder {
        for (segment in segments) {
            when (segment) {
                is Segment.Text -> text(segment.text)
                is Segment.Pronunciation -> span(ProseSpanKind.READING, "[${segment.display}]")
                is Segment.Ref -> reference(segment)
            }
        }
        return this
    }

    /** Form as one tap range, then the reading in parentheses (ENT-051). */
    fun reference(ref: Segment.Ref): ProseBuilder {
        span(ProseSpanKind.LINK, ref.form, ref)
        val reading = ref.readingDisplay
        if (!reading.isNullOrBlank()) span(ProseSpanKind.READING, " ($reading)")
        return this
    }

    fun build(): Prose = Prose(sb.toString(), spans.toList())
}

/** One numbered sense: `n. ` + inline tags + segments (ENT-040, ENT-058). */
internal fun senseProse(number: Int?, tags: List<String>, segments: List<Segment>, fallbackText: String = ""): Prose {
    val b = ProseBuilder()
    if (number != null) b.span(ProseSpanKind.NUMBER, "$number. ")
    for (tag in tags) {
        b.span(ProseSpanKind.TAG, TAG_PLACEHOLDER.toString(), label = tag)
        b.text(" ")
    }
    if (segments.isEmpty()) b.text(fallbackText) else b.segments(segments)
    return b.build()
}

/**
 * Notes that the header already shows as alternative readings are left out of the notes row
 * (ENT-057): an `also_pr` / `taiwan_pr` note without references whose reading is one of them.
 */
internal fun visibleNotes(notes: List<Note>, altReadings: List<AltReading>): List<Note> {
    if (altReadings.isEmpty()) return notes
    return notes.filterNot { note ->
        note.refs.isEmpty() && note.extra.isNullOrBlank() && !note.reading.isNullOrBlank() && altReadings.any { alt ->
            alt.kind == note.kind && (alt.reading.display == note.reading || alt.reading.canonical == note.reading)
        }
    }
}

/** Notes row (ENT-057): `Label: refs reading extra`, joined by ` • `. */
internal fun notesProse(notes: List<Note>, lang: UiLang): Prose {
    val b = ProseBuilder()
    notes.forEachIndexed { index, note ->
        if (index > 0) b.text(" • ")
        b.text(note.label.text(lang))
        val hasDetails = note.refs.isNotEmpty() || !note.reading.isNullOrBlank() || !note.extra.isNullOrBlank()
        if (hasDetails) b.text(": ")
        var first = true
        fun gap() {
            if (!first) b.text(" ")
            first = false
        }
        for (ref in note.refs) {
            gap()
            b.reference(ref)
        }
        if (!note.reading.isNullOrBlank()) {
            // The gap belongs to the reading so a hidden reading leaves no double space behind.
            b.span(ProseSpanKind.READING, (if (first) "" else " ") + note.reading)
            first = false
        }
        if (!note.extra.isNullOrBlank()) {
            gap()
            b.text(note.extra!!)
        }
    }
    return b.build()
}

/** Redirect header line (ENT-047): parsed segments, or the stored text when there are none. */
internal fun redirectProse(segments: List<Segment>, text: String): Prose =
    if (segments.isEmpty()) Prose(text, emptyList()) else ProseBuilder().segments(segments).build()

/** `Label reading • Label reading` for the header's alternative readings line (ENT-020 item 3). */
internal data class AltReadingRange(val start: Int, val end: Int, val alt: AltReading)

internal fun altReadingsLine(alts: List<AltReading>, lang: UiLang): Pair<String, List<AltReadingRange>> {
    val sb = StringBuilder()
    val ranges = ArrayList<AltReadingRange>()
    var lastLabel: String? = null
    alts.forEachIndexed { index, alt ->
        val label = alt.label.text(lang)
        // One label per run of readings with the same label; the readings in it share only the dot.
        if (index > 0) sb.append(" • ")
        if (label != lastLabel) sb.append(label).append(' ')
        lastLabel = label
        val start = sb.length
        sb.append(alt.reading.display)
        ranges += AltReadingRange(start, sb.length, alt)
    }
    return sb.toString() to ranges
}

internal fun labelText(label: Label, lang: UiLang): String = label.text(lang)
