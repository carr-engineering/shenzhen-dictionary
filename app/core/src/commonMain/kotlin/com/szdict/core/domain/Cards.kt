package com.szdict.core.domain

import com.szdict.core.api.AltReading
import com.szdict.core.api.AudioClip
import com.szdict.core.api.AudioClipKind
import com.szdict.core.api.AudioPlan
import com.szdict.core.api.DictMode
import com.szdict.core.api.EntryCard
import com.szdict.core.api.Label
import com.szdict.core.api.MandarinRow
import com.szdict.core.api.NoteLine
import com.szdict.core.api.Reading
import com.szdict.core.api.Romanisation
import com.szdict.core.api.Romanization
import com.szdict.core.api.RowRef
import com.szdict.core.api.Script
import com.szdict.core.api.Segment
import com.szdict.core.engine.Ranking
import com.szdict.core.engine.cps
import com.szdict.core.engine.cpString
import com.szdict.core.engine.hanziCount
import com.szdict.core.engine.readTextFile
import com.szdict.core.store.AltJ
import com.szdict.core.store.Codec
import com.szdict.core.store.DictionaryStore
import com.szdict.core.store.EntryRow
import com.szdict.core.store.MergeGroup
import com.szdict.core.store.NoteJ
import com.szdict.core.store.Proj
import com.szdict.core.store.SegJ
import com.szdict.core.store.SenseJ
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun romanizationOf(s: String?): Romanization = when (s) {
    "pinyin" -> Romanization.PINYIN
    "jyutping" -> Romanization.JYUTPING
    "tailo" -> Romanization.TAILO
    "poj" -> Romanization.POJ
    "teochew" -> Romanization.TEOCHEW
    "wade_giles" -> Romanization.WADE_GILES
    else -> Romanization.UNKNOWN
}

internal fun EntryRow.headword(script: Script): String = if (script == Script.SIMPLIFIED) simp else trad
internal fun EntryRow.alternate(script: Script): String = if (script == Script.SIMPLIFIED) trad else simp
internal fun EntryRow.mergeId(script: Script): Int = if (script == Script.SIMPLIFIED) mergeSimp else mergeTrad
internal fun EntryRow.reading(): Reading = Reading(readingDisplay, readingCanonical, syllables, tones.asList())
internal val EntryRow.noReading: Boolean get() = readingSource == 5 || (syllables.isEmpty() && readingDisplay.isEmpty())

internal fun readingFromCanonical(display: String, canonical: String): Reading {
    val (syl, tones) = Romanisation.parseCanonical(canonical)
    return Reading(display, canonical, syl, tones)
}

internal fun SegJ.toRef(script: Script): Segment.Ref {
    val tr = tr ?: si ?: ""
    val si = si ?: tr
    val rom = romanizationOf(rom)
    val display = d ?: c?.let { Romanisation.display(rom, it) }
    return Segment.Ref(tr, si, if (script == Script.SIMPLIFIED) si else tr, display, c, id, rom)
}

internal fun SegJ.toSegment(script: Script): Segment = when (k) {
    "t" -> Segment.Text(t ?: "")
    "r" -> Segment.Pronunciation(d ?: "", romanizationOf(rom))
    else -> toRef(script)
}

/** `refs reading extra`, space-joined (ENT-057). */
internal fun NoteJ.lineText(script: Script): String {
    val parts = ArrayList<String>()
    for (r in refs) {
        val ref = r.toRef(script)
        parts.add(if (ref.readingDisplay.isNullOrEmpty()) ref.form else "${ref.form} (${ref.readingDisplay})")
    }
    reading?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    extra?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    return parts.joinToString(" ")
}

private val EMBEDDED_VARIANT = Regex("""\s*\((?:[^()]*\bvariant of\b[^()]*)\)\s*$""")

/** SRCH-082 / ENT-049: strips a trailing "(variant of X)" from an embedded-variant sense inside a merge group. */
internal fun SenseJ.textIn(merged: Boolean): String =
    if (merged && evr != null) EMBEDDED_VARIANT.replace(t, "").ifEmpty { t } else t

/**
 * The displayed content of one merge-group member (SRCH-082, ENT-047): which senses it shows (its
 * own or its resolved redirect target's), and whether its redirect header is shown.
 */
internal class MemberView(val entry: EntryRow, val source: EntryRow, val showRedirect: Boolean, val suppressed: Boolean)

internal fun memberView(e: EntryRow, memberIds: Set<Int>, entries: Map<Int, EntryRow>): MemberView {
    val r = e.body.redirect ?: return MemberView(e, e, false, false)
    val target = if (r.resolved) r.target else null
    if (target != null && target != e.id && target in memberIds) return MemberView(e, e, false, true) // "variant of 发" inside the 发 card
    val t = target?.let { entries[it] }
    return if (t != null) MemberView(e, t, true, false) else MemberView(e, e, true, false)
}

/** Recorded-audio plan selection (MED-003…MED-007), checked against the audio catalogue when it is installed. */
internal class AudioPlanner(private val store: DictionaryStore) {
    private class Catalog(val syllables: Set<String>, val words: Set<String>)

    private var loaded = false
    private var catalog: Catalog? = null

    private fun catalog(): Catalog? {
        if (loaded) return catalog
        loaded = true
        val text = readTextFile(store.audioCatalogPath) ?: return null
        catalog = try {
            val root = Codec.json.parseToJsonElement(text).jsonObject
            val section = root[store.mode.datasetName] as? JsonObject
            fun keys(name: String): Set<String> = (section?.get(name) as? JsonArray)?.mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.content } ?: emptySet()
            Catalog(keys("syllable_keys"), keys("word_keys"))
        } catch (e: Exception) {
            null
        }
        return catalog
    }

    fun plan(e: EntryRow, script: Script): AudioPlan {
        val tts = e.headword(script)
        if (e.syllables.isEmpty()) return AudioPlan.ttsOnly(tts)
        // MED-005: lowercase, u:/ü → v, every syllable needs an explicit tone digit.
        val keys = e.syllables.mapIndexed { i, s -> if (i < e.tones.size && e.tones[i] != 0) s.replace("ü", "v").replace("u:", "v") + e.tones[i] else "" }
        val wordKey = e.audioKey ?: if (keys.all { it.isNotEmpty() }) keys.joinToString("_") else null
        val cat = catalog()
        val multi = e.charCount > 1
        if (cat == null) {
            if (store.mode == DictMode.CANTONESE) return AudioPlan(emptyList(), false, wordKey, keys, false, tts)
            val useWord = multi && e.audioKey != null
            val clips = if (useWord) listOf(AudioClip(AudioClipKind.WORD, wordKey!!)) else keys.filter { it.isNotEmpty() }.map { AudioClip(AudioClipKind.SYLLABLE, it) }
            return AudioPlan(clips, !useWord && keys.any { it.isEmpty() }, wordKey, keys, false, tts)
        }
        var gaps = false
        val sylClips = ArrayList<AudioClip>()
        for (k in keys) {
            val resolved = when {
                k.isEmpty() -> null
                k in cat.syllables -> k
                k.endsWith("5") && (k.dropLast(1) + "1") in cat.syllables -> k.dropLast(1) + "1" // MED-007 neutral-tone fallback
                else -> null
            }
            if (resolved == null) gaps = true else sylClips.add(AudioClip(AudioClipKind.SYLLABLE, resolved))
        }
        val hasWord = wordKey != null && wordKey in cat.words
        val word = if (hasWord) listOf(AudioClip(AudioClipKind.WORD, wordKey!!)) else null
        val clips: List<AudioClip>
        val planGaps: Boolean
        if (multi) {
            clips = word ?: sylClips
            planGaps = word == null && gaps
        } else if (!gaps && sylClips.isNotEmpty()) {
            clips = sylClips
            planGaps = false
        } else {
            clips = word ?: sylClips
            planGaps = word == null && gaps
        }
        return AudioPlan(clips, planGaps, wordKey, keys, true, tts)
    }
}

/** Builds [EntryCard]s with one batched hydration per call (SRCH-152): entries, merge members, redirect targets, inside-words counts. */
internal class CardBuilder(private val store: DictionaryStore, private val audio: AudioPlanner) {
    private val cantonese = store.mode == DictMode.CANTONESE

    /**
     * `within` rows of [text], computed with one batched headword lookup (DATA-102): flat
     * (start, length, entry id) triples in DATA-101 order. [includeWhole] keeps the full span.
     */
    suspend fun withinOnDemand(text: String, includeWhole: Boolean = false): IntArray {
        val cps = text.cps()
        val n = cps.size
        if (n < 2 && !includeWhole) return IntArray(0)
        val maxLen = minOf(store.maxWordLen, n)
        val spans = ArrayList<Triple<Int, Int, String>>()
        for (len in maxLen downTo 1) for (start in 0..n - len) {
            if (len == n && !includeWhole) continue
            spans.add(Triple(start, len, com.szdict.core.engine.Segmenter.lookupKey(cps.cpString(start, start + len))))
        }
        val hits = store.headwordExact(spans.map { it.third }.toSet())
        val order = Ranking.projComparator(false)
        val out = ArrayList<Int>()
        for ((start, len, key) in spans) {
            val hs = hits[key] ?: continue
            for (p in hs.map { it.proj }.distinctBy { it.id }.sortedWith(order)) {
                out.add(start); out.add(len); out.add(p.id)
            }
        }
        return out.toIntArray()
    }

    /** `within` triples for entries: precomputed rows, or computed on demand for long headwords. */
    suspend fun withinRows(entries: Collection<EntryRow>): Map<Int, IntArray> {
        val wanted = entries.filter { it.charCount >= 2 && it.simp.hanziCount() >= 2 }
        if (wanted.isEmpty()) return emptyMap()
        val out = HashMap(store.within(wanted.map { it.id }))
        for (e in wanted) if (e.id !in out && e.charCount > 8) out[e.id] = withinOnDemand(e.simp)
        return out
    }

    suspend fun build(refs: List<RowRef>, script: Script, merge: Boolean = true, exactInside: Boolean = true): List<EntryCard> {
        if (refs.isEmpty()) return emptyList()
        val entries = HashMap(store.entries(refs.map { it.entryId }.toSet()))
        val groups: Map<Int, MergeGroup> = if (merge) {
            val g = store.mergeGroups(entries.values.map { it.mergeId(script) }.filter { it >= 0 }.toSet())
            val members = g.values.flatMap { it.members.asList() }.filter { it !in entries }
            if (members.isNotEmpty()) entries.putAll(store.entries(members))
            g
        } else emptyMap()
        val targets = entries.values.mapNotNull { e -> e.body.redirect?.takeIf { it.resolved }?.target }.filter { it !in entries }.toSet()
        if (targets.isNotEmpty()) entries.putAll(store.entries(targets))
        val labels = store.labels()

        val cardEntries = refs.mapNotNull { entries[it.entryId] }.distinctBy { it.id }
        val within = withinRows(cardEntries)
        val insideCount = HashMap<Int, Int>()
        if (within.isNotEmpty()) {
            val projs: Map<Int, Proj> = if (exactInside) {
                val ids = HashSet<Int>()
                for (rows in within.values) for (i in 2 until rows.size step 3) ids.add(rows[i])
                store.projections(ids.toIntArray())
            } else emptyMap()
            for ((id, rows) in within) {
                if (!exactInside) {
                    insideCount[id] = rows.size / 3
                    continue
                }
                val seen = HashSet<Long>()
                var i = 0
                while (i + 2 < rows.size) {
                    val p = projs[rows[i + 2]]
                    if (p != null) {
                        val g = Ranking.mergeId(p, script)
                        val who = if (g >= 0) g.toLong() + 0x40000000L else p.id.toLong()
                        seen.add((rows[i].toLong() shl 48) or (rows[i + 1].toLong() shl 40) or who)
                    }
                    i += 3
                }
                insideCount[id] = seen.size
            }
        }
        return refs.mapNotNull { ref ->
            val e = entries[ref.entryId] ?: return@mapNotNull null
            card(e, ref, script, groups[e.mergeId(script)].takeIf { merge }, entries, labels, insideCount[e.id] ?: 0)
        }
    }

    fun altReadings(e: EntryRow, labels: Map<String, Label>): List<AltReading> = e.body.alt.mapIndexed { i, a -> a.toAlt(i, labels) }

    private fun AltJ.toAlt(ordinal: Int, labels: Map<String, Label>) =
        AltReading(kind, labels[kind] ?: Label.missing(kind), readingFromCanonical(d.ifEmpty { Romanisation.display(store.mode, c) }, c), ordinal)

    fun provenance(e: EntryRow, labels: Map<String, Label>): Label? {
        val b = e.body.blocks.singleOrNull() ?: return if (e.isUnihan) labels["block_unihan"] ?: Label("block_unihan", "Unicode (Unihan)", "Unicode（Unihan）", "Unicode（Unihan）") else null
        return when (b.kind) {
            "unihan" -> labels[b.label.ifEmpty { "block_unihan" }] ?: Label("block_unihan", "Unicode (Unihan)", "Unicode（Unihan）", "Unicode（Unihan）")
            "hsk_patch" -> labels[b.label.ifEmpty { "block_hsk_patch" }] ?: Label("block_hsk_patch", "HSK parser patch", "HSK parser patch", "HSK parser patch")
            else -> null
        }
    }

    fun hskLevels(members: List<EntryRow>): List<Int> {
        val out = sortedSetOf<Int>()
        for (m in members) {
            m.hsk.forEach { out.add(it.l) }
            m.hskVia.forEach { out.add(it.l) }
        }
        return out.toList()
    }

    private fun card(
        e: EntryRow, ref: RowRef, script: Script, group: MergeGroup?, entries: Map<Int, EntryRow>,
        labels: Map<String, Label>, insideCount: Int,
    ): EntryCard {
        val members: List<EntryRow> = group?.members?.asList()?.mapNotNull { entries[it] }?.takeIf { it.size > 1 && it.any { m -> m.id == e.id } } ?: listOf(e)
        val merged = members.size > 1
        val memberIds = members.mapTo(HashSet()) { it.id }
        val definitions = ArrayList<String>()
        val seen = HashSet<String>()
        val redirects = ArrayList<String>()
        val notes = ArrayList<NoteLine>()
        for (m in members) {
            val view = memberView(m, memberIds, entries)
            if (view.showRedirect) m.body.redirect?.text?.takeIf { it.isNotEmpty() }?.let { if (it !in redirects) redirects.add(it) }
            if (!view.suppressed && (view.source !== m || m.body.redirect == null || m.body.senses.isNotEmpty())) {
                for (s in view.source.body.senses) {
                    val t = s.textIn(merged)
                    if (t.isNotEmpty() && seen.add(t)) definitions.add(t)
                }
            }
            for (n in m.body.notes) notes.add(NoteLine(n.kind, labels[n.label.ifEmpty { n.kind }] ?: Label.missing(n.kind), n.lineText(script)))
        }
        val head = e.headword(script)
        val alternates = (if (merged && group != null && group.variants.isNotEmpty()) group.variants else listOf(e.alternate(script))).filter { it != head }
        val matchedAlt = when {
            ref.altOrdinal >= 0 -> e.body.alt.getOrNull(ref.altOrdinal)?.toAlt(ref.altOrdinal, labels)
            ref.altKey != null -> e.body.alt.withIndex().firstOrNull { (_, a) -> Romanisation.parseCanonical(a.c).first.joinToString(" ") == ref.altKey }?.let { it.value.toAlt(it.index, labels) }
            else -> null
        }
        val mandarinRow = if (ref.mandarinRow) e.body.blocks.firstOrNull { it.kind == "mandarin" || it.kind == "hsk_patch" }?.let { b ->
            // Reading groups index senses absolutely (or relative to the block): pick the one holding the matched sense.
            fun first(r: com.szdict.core.store.BlockReadingJ) = if (r.first >= b.first && r.first + r.count <= b.first + b.count) r.first else b.first + r.first
            val gi = b.readings.indexOfFirst { r -> ref.sense >= first(r) && ref.sense < first(r) + r.count }.let { if (it < 0) 0 else it }
            val r = b.readings.getOrNull(gi)
            if (r == null) MandarinRow(0, Reading.EMPTY, e.body.senses.drop(b.first).take(b.count).map { it.t }, ref.sense)
            else MandarinRow(gi, readingFromCanonical(r.d.ifEmpty { Romanisation.pinyinToMarked(r.c) }, r.c), e.body.senses.drop(first(r)).take(r.count).map { it.t }, ref.sense)
        } else null
        val ordered = listOf(e) + members.filter { it.id != e.id }
        return EntryCard(
            id = e.id, naturalKey = e.naturalKey, simplified = e.simp, traditional = e.trad, headword = head,
            alternateForms = alternates, reading = e.reading(),
            toneColourable = e.syllables.isNotEmpty() && e.syllables.size == e.charCount, noReading = e.noReading,
            definitions = definitions, redirectHeaders = redirects, notes = notes, hskLevels = hskLevels(members),
            provenance = provenance(e, labels), isProperNoun = e.isProper, isUnihanOnly = e.isUnihan,
            hasCantoBlock = cantonese && e.hasCanto, frequencyRank = e.freqRank, hanziCount = head.hanziCount(),
            insideCount = insideCount, memberIds = ordered.map { it.id }, matchedAlt = matchedAlt, mandarinRow = mandarinRow,
            audio = audio.plan(e, script),
        )
    }
}
