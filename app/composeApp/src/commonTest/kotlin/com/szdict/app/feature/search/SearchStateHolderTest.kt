package com.szdict.app.feature.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.Script
import com.szdict.core.api.SearchOptions
import com.szdict.core.api.SearchResult
import com.szdict.core.api.SearchSide
import com.szdict.core.api.SectionKind
import com.szdict.core.api.SettingsKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStateHolderTest {

    /** Counts searches and makes each take [searchMs] of virtual time. */
    private class SlowDictionary(private val inner: Dictionary, private val searchMs: Long) : Dictionary by inner {
        var started = 0
        var finished = 0
        var failNext = false

        override fun searchProgressive(query: String, script: Script, options: SearchOptions): Flow<SearchResult> = flow {
            started++
            delay(searchMs)
            if (failNext) { failNext = false; error("boom") }
            emit(inner.search(query, script, options))
            finished++
        }
    }

    private class Storage(private val stored: Map<String, String>, private val loadMs: Long) : SettingsStorage {
        val written = LinkedHashMap<String, String?>()
        override val values: Flow<Map<String, String>> = flow { delay(loadMs); emit(stored) }
        override suspend fun update(changes: Map<String, String?>) { written.putAll(changes) }
        override fun flushBlocking(changes: Map<String, String?>) { written.putAll(changes) }
    }

    private class Fixture(scope: TestScope, stored: Map<String, String> = emptyMap(), loadMs: Long = 0, searchMs: Long = 20) {
        val dictionary = SlowDictionary(FakeDictionary(), searchMs)
        val storage = Storage(stored, loadMs)
        val session = AppSession(
            AppEnv(
                openDictionary = { dictionary },
                settingsStorage = storage,
                audio = NoAudioPlayer,
                speaker = NoSpeaker,
                clipboard = object : Clipboard {
                    override val hasText: Flow<Boolean> = flowOf(false)
                    override fun refresh() {}
                    override fun readText(): String? = null
                    override fun writeText(text: String) {}
                },
                translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
                links = object : LinkOpener { override fun open(url: String) {} },
            ),
            scope.backgroundScope,
        )
        val holder = SearchStateHolder(session, scope.backgroundScope, epochDay = { 20_000L }).also { it.start() }

        fun type(text: String) = holder.onFieldChange(TextFieldValue(text, TextRange(text.length)))
    }

    @Test
    fun keystrokesAndBackspacesCompleteOneSearch() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        val word = "nihaowomen"
        for (i in 1..word.length) f.type(word.take(i))          // ten keystrokes
        for (i in word.length - 1 downTo 5) f.type(word.take(i)) // five backspaces
        assertTrue(f.holder.searching)
        advanceTimeBy(500)
        assertEquals(1, f.dictionary.started)
        assertEquals(1, f.dictionary.finished)
        assertEquals(1, f.holder.completedSearches)
        assertEquals("nihao", f.holder.displayed?.result?.query)
        assertFalse(f.holder.searching)
        assertTrue(f.holder.countsCurrent)
    }

    @Test
    fun supersededSearchNeverOverwritesNewerState() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("ni")
        advanceTimeBy(10)            // "ni" is in flight
        f.type("wo")
        advanceTimeBy(500)
        assertEquals("wo", f.holder.displayed?.result?.query)
        assertEquals(1, f.holder.completedSearches)
    }

    @Test
    fun blankQueryAppliesEmptyStateInTheSameFrame() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("ni")
        advanceTimeBy(500)
        assertNotNull(f.holder.displayed)
        f.type("nih")
        advanceTimeBy(5)             // in flight
        f.holder.clear()
        // No time passes: the empty state is already applied and nothing is running (GLB-042).
        assertNull(f.holder.displayed)
        assertFalse(f.holder.searching)
        advanceTimeBy(500)
        assertNull(f.holder.displayed)
        assertEquals(1, f.holder.completedSearches)
        assertEquals("", f.holder.query)
    }

    @Test
    fun spacesOnlyIsBlank() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("   ")
        assertNull(f.holder.displayed)
        assertEquals(0, f.dictionary.started)
    }

    @Test
    fun lateSettingsEmissionDoesNotOverwriteTyping() = runTest {
        val f = Fixture(this, stored = mapOf(SettingsKeys.lastSearchQuery(DictMode.MANDARIN) to "中国"), loadMs = 200)
        f.type("ni")                 // before the settings have loaded
        advanceTimeBy(2_000)
        assertTrue(f.session.settingsLoaded)
        assertEquals("ni", f.holder.query)
        assertEquals("ni", f.holder.displayed?.result?.query)
        assertEquals("ni", f.session.settings.value.lastSearchQuery)
        assertEquals("ni", f.storage.written[SettingsKeys.lastSearchQuery(DictMode.MANDARIN)])
    }

    @Test
    fun lastQueryIsRestoredNormalisedAndExecuted() = runTest {
        val f = Fixture(this, stored = mapOf(SettingsKeys.lastSearchQuery(DictMode.MANDARIN) to "你好\n"), loadMs = 50)
        advanceTimeBy(1_000)
        assertEquals("你好 ", f.holder.query)
        assertEquals(SearchSide.CHINESE, f.holder.side)
        assertNotNull(f.holder.displayed)
        assertNotNull(f.holder.wordOfDay)
        assertTrue(f.holder.popular.isNotEmpty())
    }

    @Test
    fun queryIsPersistedDebounced() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("ni")
        advanceTimeBy(700)
        assertNull(f.storage.written[SettingsKeys.lastSearchQuery(DictMode.MANDARIN)])
        advanceTimeBy(100)
        assertEquals("ni", f.storage.written[SettingsKeys.lastSearchQuery(DictMode.MANDARIN)])
    }

    @Test
    fun sideSelectionAndRememberedSide() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        assertEquals(SearchSide.ENGLISH, f.holder.side)          // SRCH-027
        f.type("nh"); advanceTimeBy(100)
        assertEquals(SearchSide.CHINESE, f.holder.side)          // forced: no English match
        f.holder.onManualSide(SearchSide.ENGLISH)
        assertEquals(SearchSide.ENGLISH, f.holder.side)          // manual is authoritative (SRCH-025)
        f.type("fa"); advanceTimeBy(100)                         // 发 fā / fate, family: both sides answer
        assertEquals(SearchSide.ENGLISH, f.holder.side)          // remembered side
        f.holder.onManualSide(SearchSide.CHINESE)
        assertEquals(SearchSide.CHINESE, f.session.settings.value.searchSide)   // ambiguous query teaches (SRCH-026)
        f.type("你好"); advanceTimeBy(100)
        f.holder.onManualSide(SearchSide.ENGLISH)
        assertEquals(SearchSide.CHINESE, f.session.settings.value.searchSide)   // forced query does not
        f.type("fa"); advanceTimeBy(100)
        assertEquals(SearchSide.CHINESE, f.holder.side)          // remembered side again
        // A remembered side with no result never wins over the side that answers the query:
        // `water` has English results only, so it opens on the English page — forced, not remembered.
        f.type("water"); advanceTimeBy(100)
        assertEquals(SearchSide.ENGLISH, f.holder.side)
        assertEquals(SearchSide.CHINESE, f.session.settings.value.searchSide)
        f.holder.onManualSide(SearchSide.CHINESE)                // looking at the empty page teaches nothing
        f.holder.onManualSide(SearchSide.ENGLISH)
        assertEquals(SearchSide.CHINESE, f.session.settings.value.searchSide)
        f.type("fa"); advanceTimeBy(100)
        assertEquals(SearchSide.CHINESE, f.holder.side)
    }

    @Test
    fun emptyRememberedSideYieldsToTheOtherSide() = runTest {
        // The other direction: English remembered (fresh install), a query only the Chinese side answers.
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("good"); advanceTimeBy(100)
        assertEquals(SearchSide.ENGLISH, f.holder.side)          // English results only
        f.type("xiexie"); advanceTimeBy(100)
        assertEquals(SearchSide.CHINESE, f.holder.side)          // Chinese results only
        assertEquals(SearchSide.ENGLISH, f.session.settings.value.searchSide)
    }

    @Test
    fun manualSideWhileInFlightIsKept() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("good"); advanceTimeBy(100)
        f.type("nh")
        advanceTimeBy(5)
        f.holder.onManualSide(SearchSide.CHINESE)
        f.holder.onManualSide(SearchSide.ENGLISH)
        advanceTimeBy(100)
        assertEquals(SearchSide.ENGLISH, f.holder.side)          // not overridden by the forced side
        assertEquals(SearchSide.ENGLISH, f.session.settings.value.searchSide)
    }

    @Test
    fun failureKeepsPreviousResultsAndNextKeystrokeRecovers() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("ni"); advanceTimeBy(100)
        f.dictionary.failNext = true
        f.type("nih"); advanceTimeBy(100)
        assertNotNull(f.holder.error)
        assertEquals("ni", f.holder.displayed?.result?.query)   // SRCH-172
        assertFalse(f.holder.countsCurrent)
        f.type("niha"); advanceTimeBy(100)
        assertNull(f.holder.error)
        assertEquals("niha", f.holder.displayed?.result?.query)
    }

    @Test
    fun queryChangeClearsDetailStackAndScrollsToTop() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        var scrolls = 0
        f.holder.onScrollToTop = { scrolls++ }
        f.session.openEntry(1)
        f.type("ni")
        assertTrue(f.session.routes.isEmpty())                   // SRCH-133
        assertEquals(1, scrolls)
        advanceTimeBy(100)
        assertEquals(2, scrolls)                                 // again when the response is applied
        // A caret move alone is not a query change.
        f.holder.onFieldChange(TextFieldValue("ni", TextRange(1)))
        assertEquals(2, scrolls)
        assertEquals(1, f.dictionary.started)
    }

    @Test
    fun scriptChangeReRunsWithoutChangingSide() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("我们"); advanceTimeBy(100)
        f.holder.onManualSide(SearchSide.ENGLISH)
        f.session.updateSettings { it.withScript(DictMode.MANDARIN, Script.TRADITIONAL) }
        advanceTimeBy(100)
        assertEquals(Script.TRADITIONAL, f.holder.displayed?.result?.script)
        assertEquals(SearchSide.ENGLISH, f.holder.side)
        assertEquals(2, f.dictionary.started)
    }

    @Test
    fun handwritingSinkEditsAtTheCaret() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.holder.onFieldChange(TextFieldValue("你好", TextRange(1)))
        f.holder.handwritingSink.insert("们")
        assertEquals("你们好", f.holder.query)
        assertEquals(TextRange(2), f.holder.input.selection)
        f.holder.handwritingSink.deleteBackward()
        assertEquals("你好", f.holder.query)
        f.holder.paste("line one\r\nline two")
        assertEquals("line one line two", f.holder.query)       // SRCH-011
    }

    @Test
    fun pagePlanAnchorsAndWindowedHydration() = runTest {
        val f = Fixture(this)
        advanceTimeBy(100)
        f.type("生"); advanceTimeBy(100)
        val shown = assertNotNull(f.holder.displayed)
        val plan = buildPagePlan(shown.chinese, hasError = false)
        assertEquals(listOf("Exact", "Starts", "Contains"), plan.chips.map { it.label })
        // Every chip points at the first list item of its section.
        var index = 0
        val starts = HashMap<SectionKind, Int>()
        plan.blocks.forEach { block ->
            when (block) {
                is Block.Header -> starts.getOrPut(block.state.section.kind) { index }
                is Block.Groups -> starts.getOrPut(block.state.section.kind) { index }
                is Block.Rows -> starts.getOrPut(block.state.section.kind) { index }
                else -> {}
            }
            index += block.count
        }
        assertEquals(listOf(starts[SectionKind.GROUPS], starts[SectionKind.STARTS_WITH], starts[SectionKind.CONTAINS]), plan.chips.map { it.itemIndex })
        val rows = shown.chinese.sections.first { it.section.kind == SectionKind.CONTAINS }
        assertEquals(rows.total, rows.keys.toSet().size)         // SRCH-169
        assertEquals(listOf(Block.NoMatches), buildPagePlan(shown.english, hasError = false).blocks)
    }
}
