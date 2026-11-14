package com.szdict.app.feature.entry

import com.szdict.core.api.AltReading
import com.szdict.core.api.Label
import com.szdict.core.api.Note
import com.szdict.core.api.Reading
import com.szdict.core.api.Romanization
import com.szdict.core.api.Segment
import com.szdict.core.api.UiLang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProseTest {
    private fun ref(form: String, reading: String?, target: Int? = null) =
        Segment.Ref(form, form, form, reading, reading, target, Romanization.PINYIN)

    private fun label(key: String, en: String, zh: String) = Label(key, en, zh, zh)

    @Test
    fun senseHasNumberLinkAndReadingRanges() {
        val prose = senseProse(
            number = 3,
            tags = emptyList(),
            segments = listOf(Segment.Text("variant of "), ref("一点", "yī diǎn", 7), Segment.Text(" (a bit) "), Segment.Pronunciation("yī", Romanization.PINYIN)),
        )
        assertEquals("3. variant of 一点 (yī diǎn) (a bit) [yī]", prose.text)
        val number = prose.spans.single { it.kind == ProseSpanKind.NUMBER }
        assertEquals("3. ", prose.text.substring(number.start, number.end))
        // ENT-055: a multi-code-point reference is one tap range covering the form only.
        val link = prose.links.single()
        assertEquals("一点", prose.text.substring(link.start, link.end))
        assertEquals(7, link.ref?.targetId)
        val readings = prose.spans.filter { it.kind == ProseSpanKind.READING }.map { prose.text.substring(it.start, it.end) }
        assertEquals(listOf(" (yī diǎn)", "[yī]"), readings)
        // Ordinary parentheses stay plain text (ENT-051) and survive hiding the readings (GLB-120).
        assertEquals("3. variant of 一点 (a bit)", prose.textWithoutReadings())
    }

    @Test
    fun surrogatePairReferenceKeepsItsFullRange() {
        val form = "𠮷野"   // 𠮷野
        val prose = senseProse(1, emptyList(), listOf(Segment.Text("see "), ref(form, null)))
        val link = prose.links.single()
        assertEquals(form, prose.text.substring(link.start, link.end))
        assertEquals(3, link.end - link.start)
        assertTrue(prose.spans.none { it.kind == ProseSpanKind.READING })
    }

    @Test
    fun tagsBecomePlaceholdersBeforeTheText() {
        val prose = senseProse(1, listOf("Idiom", "Figurative"), emptyList(), fallbackText = "to hit the mark")
        val tags = prose.spans.filter { it.kind == ProseSpanKind.TAG }
        assertEquals(listOf("Idiom", "Figurative"), tags.map { it.label })
        tags.forEach { assertEquals(TAG_PLACEHOLDER.toString(), prose.text.substring(it.start, it.end)) }
        assertTrue(prose.text.endsWith("to hit the mark"))
        assertTrue(tags.all { it.start >= 3 })   // after the number
    }

    @Test
    fun notesRowJoinsLabelsReferencesReadingAndExtra() {
        val notes = listOf(
            Note("classifier", label("classifier", "Classifier", "量词"), listOf(ref("个", "gè"), ref("位", "wèi")), null, null),
            Note("also_pr", label("also_pr", "Also pronounced", "又读"), emptyList(), "shéi", "in speech"),
            Note("surname", label("surname", "Surname", "姓"), emptyList(), null, null),
        )
        val en = notesProse(notes, UiLang.EN)
        assertEquals("Classifier: 个 (gè) 位 (wèi) • Also pronounced: shéi in speech • Surname", en.text)
        assertEquals(listOf("个", "位"), en.links.map { en.text.substring(it.start, it.end) })
        assertEquals("Classifier: 个 位 • Also pronounced: in speech • Surname", en.textWithoutReadings())
        assertTrue(notesProse(notes, UiLang.ZH_HANS).text.startsWith("量词: 个 (gè)"))
    }

    @Test
    fun headerAlternativeReadingsAreNotRepeatedInTheNotesRow() {
        val taiwan = label("taiwan_pr", "Taiwan pronunciation", "台湾读音")
        val alt = AltReading("taiwan_pr", taiwan, Reading("lè sè", "le4 se4", listOf("le", "se"), listOf(4, 4)), 0)
        val notes = listOf(
            Note("taiwan_pr", taiwan, emptyList(), "lè sè", null),
            // A partial alternative reading stays a note (ENT-020 item 3).
            Note("taiwan_pr", taiwan, emptyList(), "sè", null),
            Note("see", label("see", "See", "见"), listOf(ref("拉圾", null)), null, null),
        )
        val shown = visibleNotes(notes, listOf(alt))
        assertEquals(listOf("sè", null), shown.map { it.reading })
        assertEquals(notes, visibleNotes(notes, emptyList()))
    }

    @Test
    fun alternativeReadingsLine() {
        val a = AltReading("taiwan_pr", label("taiwan_pr", "Taiwan pronunciation", "台湾读音"), Reading("lè sè", "le4 se4", listOf("le", "se"), listOf(4, 4)), 0)
        val b = AltReading("also_pr", label("also_pr", "Also pronounced", "又读"), Reading("lā jī", "la1 ji1", listOf("la", "ji"), listOf(1, 1)), 1)
        val (line, ranges) = altReadingsLine(listOf(a, b), UiLang.EN)
        assertEquals("Taiwan pronunciation lè sè • Also pronounced lā jī", line)
        assertEquals(listOf("lè sè", "lā jī"), ranges.map { line.substring(it.start, it.end) })
    }

    @Test
    fun alsoPronouncedLabelOncePerRun() {
        val label = label("also_pr", "Also pronounced", "又读")
        val a = AltReading("also_pr", label, Reading("lā jī", "la1 ji1", listOf("la", "ji"), listOf(1, 1)), 0)
        val b = AltReading("also_pr", label, Reading("lè sè", "le4 se4", listOf("le", "se"), listOf(4, 4)), 1)
        val (line, _) = altReadingsLine(listOf(a, b), UiLang.EN)
        assertEquals("Also pronounced lā jī • lè sè", line)
    }

    @Test
    fun redirectFallsBackToStoredText() {
        val plain = redirectProse(emptyList(), "variant of 逼格")
        assertEquals("variant of 逼格", plain.text)
        assertTrue(plain.spans.isEmpty())
        val parsed = redirectProse(listOf(Segment.Text("variant of "), ref("逼格", "bī gé")), "ignored")
        assertEquals("variant of 逼格 (bī gé)", parsed.text)
        assertNull(parsed.links.single().ref?.targetId)
    }

    @Test
    fun exampleTapTargetsArePerCodePoint() {
        val text = "我是𠮷。OK"
        // Every Hanzi starts a tap range; the supplementary character keeps one offset (MED-043).
        assertEquals(listOf(0, 1, 2), exampleTapOffsets(text, emptyList()))
        assertEquals(listOf(0, 1, 2, 5), exampleTapOffsets(text, listOf(5)))
    }
}
