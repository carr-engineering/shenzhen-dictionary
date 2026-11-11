package com.szdict.app.feature.hsk

import com.szdict.core.api.DailyPick
import com.szdict.core.api.DictMode
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.HskLevelInfo
import com.szdict.core.api.Script
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HskGridModelTest {
    private val identity = HskIdentity(DictMode.MANDARIN, Script.SIMPLIFIED, HskSection.WORDS, 0)

    private suspend fun entries(level: Int) = hskWordItems(FakeDictionary().hsk(level..level, Script.SIMPLIFIED))

    @Test
    fun levelCountsUseTheSectionCountAndDropEmptyLevels() {
        val infos = listOf(HskLevelInfo(2, 200, 198, 0), HskLevelInfo(1, 300, 300, 246), HskLevelInfo(7, 5600, 5590, 1148), HskLevelInfo(3, 0, 0, 0))
        assertEquals(listOf(HskLevelCount(1, 300), HskLevelCount(2, 200), HskLevelCount(7, 5600)), hskLevelCounts(infos, HskSection.WORDS))
        assertEquals(listOf(HskLevelCount(1, 246), HskLevelCount(7, 1148)), hskLevelCounts(infos, HskSection.CHARACTERS))
    }

    @Test
    fun groupsCardsUnderAscendingLevelHeadersInStorageOrder() = runTest {
        val levels = listOf(HskLevelCount(1, 300), HskLevelCount(2, 200), HskLevelCount(3, 500))
        val loaded = mapOf(1 to entries(1), 2 to entries(2), 3 to entries(3))
        val model = buildHskGrid(identity, levels, loaded)
        assertTrue(model.complete)
        assertEquals(listOf(1, 2, 3), model.headerIndexByLevel.keys.toList())
        assertEquals(0, model.headerIndexByLevel[1])
        for ((level, index) in model.headerIndexByLevel) {
            val header = model.items[index] as HskGridItem.LevelHeader
            assertEquals(level, header.level)
            // HSK-021: the header reports the syllabus count, not the number of cards.
            assertEquals(levels.first { it.level == level }.count, header.count)
        }
        // Levels ascend and each card sits under its own level's header, in the given order.
        assertEquals(model.items.map { it.level }, model.items.map { it.level }.sorted())
        val levelOne = model.items.filterIsInstance<HskGridItem.Entry>().filter { it.level == 1 }
        assertEquals(loaded.getValue(1).map { it.key }, levelOne.map { it.key })
        assertEquals(1 + loaded.getValue(1).size, model.headerIndexByLevel[2])
        assertEquals(2, model.levelAt(model.headerIndexByLevel.getValue(2)))
    }

    @Test
    fun keysAreUniqueAndDuplicateRowsAreDropped() = runTest {
        val one = entries(1)
        val model = buildHskGrid(identity, listOf(HskLevelCount(1, 300)), mapOf(1 to one + one.take(2)))
        val keys = model.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(1 + one.size, model.items.size)
    }

    @Test
    fun emptyOrUnloadedLevelsGetNoHeader() = runTest {
        val levels = listOf(HskLevelCount(1, 300), HskLevelCount(2, 200), HskLevelCount(4, 1000))
        val partial = buildHskGrid(identity, levels, mapOf(1 to entries(1)))
        assertFalse(partial.complete)
        assertEquals(listOf(1), partial.headerIndexByLevel.keys.toList())
        // Unloaded levels stay in the selector; a level loaded with no rows is omitted (HSK-081).
        assertEquals(listOf(1, 2, 4), partial.levels.map { it.level })
        val whole = buildHskGrid(identity, levels, mapOf(1 to entries(1), 2 to emptyList(), 4 to entries(4)))
        assertTrue(whole.complete)
        assertEquals(listOf(1, 4), whole.levels.map { it.level })
        assertEquals(listOf(1, 4), whole.headerIndexByLevel.keys.toList())
        assertTrue(buildHskGrid(identity, emptyList(), emptyMap()).items.isEmpty())
    }

    @Test
    fun characterCardsAreKeyedPerLevel() = runTest {
        val cards = FakeDictionary().hskChars(1..2, Script.SIMPLIFIED)
        val items = hskCharItems(cards)
        assertEquals(cards.size, items.map { it.key }.toSet().size)
        assertTrue(items.all { it.card.hskLevels.isEmpty() })
    }

    @Test
    fun columnCountFollowsWidth() {
        assertEquals(1, hskColumnCount(360f))
        assertEquals(1, hskColumnCount(599f))
        assertEquals(2, hskColumnCount(600f))
        assertEquals(2, hskColumnCount(839f))
        assertEquals(3, hskColumnCount(840f))
        assertEquals(listOf(138, 164, 124, 178, 146, 156, 132, 172), HskSkeletonHeights)
    }

    @Test
    fun phaseShowsSkeletonOnlyBeforeFirstContent() = runTest {
        val model = buildHskGrid(identity, listOf(HskLevelCount(1, 300)), mapOf(1 to entries(1)))
        assertEquals(HskPhase.LOADING, hskPhase(loading = true, model = null))
        assertEquals(HskPhase.CONTENT, hskPhase(loading = true, model = model))
        assertEquals(HskPhase.EMPTY, hskPhase(loading = false, model = null))
        assertEquals(HskPhase.EMPTY, hskPhase(loading = false, model = buildHskGrid(identity, emptyList(), emptyMap())))
    }

    // ---- widget projection and daily pick (SET-063, SET-066) ----

    private val rows = listOf(
        WidgetRow(30, "00000000000000aa", listOf(1), "爱", "愛", "ài", "to love"),
        WidgetRow(10, "00000000000000bb", listOf(2, 4), "半", "半", "bàn", "half"),
        WidgetRow(30, "00000000000000aa", listOf(1), "爱", "愛", "ài", "to love"),
        WidgetRow(20, "00000000000000cc", listOf(7), "女儿", "女兒", "nǚ ér", "daughter"),
    )

    @Test
    fun dailyPickMatchesTheSharedFunction() {
        assertNull(WidgetPick.pick(rows, 0, 20736))
        assertNull(WidgetPick.pick(rows, 0b0100, 20736))   // no row in level 3
        for (day in listOf(-1L, 0L, 20736L, 20737L)) {
            val all = WidgetPick.pick(rows, 0x7F, day)
            // Candidates: de-duplicated by entry id, sorted by entry id.
            assertEquals(listOf(10, 20, 30)[DailyPick.index(day, 0x7F, 3)], all?.id)
            assertEquals(WidgetPick.pick(rows, 0x7F, day), all)
        }
        assertEquals(30, WidgetPick.pick(rows, 0b0001, 5)?.id)
        assertEquals(10, WidgetPick.pick(rows, 0b1000, 5)?.id)  // extra level counts
        assertEquals(0b101, WidgetPick.toggle(0b001, 3))
        assertTrue(WidgetPick.contains(0x40, 7))
        assertFalse(WidgetPick.contains(0x40, 8))
        assertEquals("https://szdict.com/yue/word/00000000000000cc", WidgetPick.url(DictMode.CANTONESE, rows[3]))
    }

    @Test
    fun projectionParsesTheContractFormat() {
        val json = """{"dataset_version":"2026.11","rows":[{"id":1,"key":"0123456789abcdef","levels":[1],"simp":"爱","trad":"愛","reading":"ài","gloss":"to love; affection","future":true}]}"""
        val projection = assertNotNull(WidgetProjection.parse(json))
        assertEquals("2026.11", projection.datasetVersion)
        assertEquals(WidgetRow(1, "0123456789abcdef", listOf(1), "爱", "愛", "ài", "to love; affection"), projection.rows.single())
        assertNull(WidgetProjection.parse("not json"))
        assertEquals("mandarin-widget.json", WidgetProjection.assetName(DictMode.MANDARIN))
        assertEquals("cantonese-widget.json", WidgetProjection.assetName(DictMode.CANTONESE))
    }

    @Test
    fun readingYieldsTonesAndAudioKeys() {
        val pinyin = WidgetReading.syllables(DictMode.MANDARIN, "nǚ ér")
        assertEquals(listOf(3, 2), pinyin.map { it.tone })
        assertEquals(listOf("nv3", "er2"), pinyin.map { it.audioKey })
        assertEquals(listOf("peng2", "you5"), WidgetReading.syllables(DictMode.MANDARIN, "péng you").map { it.audioKey })
        assertEquals(listOf("zhong1", "guo2"), WidgetReading.syllables(DictMode.MANDARIN, "Zhōng guó").map { it.audioKey })
        assertEquals(listOf(listOf("cmn-word/peng2_you5"), listOf("cmn-syl/peng2", "cmn-syl/you5")),
            WidgetReading.clipCandidates(DictMode.MANDARIN, "péng you"))
        val jyutping = WidgetReading.syllables(DictMode.CANTONESE, "nei5 hou2")
        assertEquals(listOf(5, 2), jyutping.map { it.tone })
        assertEquals(listOf(listOf("yue-syl/nei5", "yue-syl/hou2")), WidgetReading.clipCandidates(DictMode.CANTONESE, "nei5 hou2"))
        assertTrue(WidgetReading.clipCandidates(DictMode.MANDARIN, "").isEmpty())
    }
}
