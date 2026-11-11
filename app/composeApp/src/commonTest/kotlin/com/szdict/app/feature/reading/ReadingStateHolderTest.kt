package com.szdict.app.feature.reading

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.feature.conversion.ConversionAction
import com.szdict.app.feature.conversion.ConversionSelection
import com.szdict.app.feature.conversion.CountingDictionary
import com.szdict.app.feature.conversion.FakeScriptConverter
import com.szdict.app.feature.conversion.ScriptVariant
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.DictMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingStateHolderTest {
    private class FakeClipboard(var text: String? = null) : Clipboard {
        val state = MutableStateFlow(text != null)
        var written: String? = null
        override val hasText: Flow<Boolean> = state
        override fun refresh() { state.value = !text.isNullOrBlank() }
        override fun readText(): String? = text?.takeIf { it.isNotBlank() }
        override fun writeText(text: String) { written = text }
    }

    private class FakeTranslator : Translator {
        val sent = ArrayList<Pair<String, String>>()
        override fun translate(text: String, sourceLanguageTag: String) { sent += text to sourceLanguageTag }
    }

    private object MemorySettings : SettingsStorage {
        override val values: Flow<Map<String, String>> = flowOf(emptyMap())
        override suspend fun update(changes: Map<String, String?>) {}
        override fun flushBlocking(changes: Map<String, String?>) {}
    }

    private class Fixture(scope: TestScope) {
        val clipboard = FakeClipboard()
        val translator = FakeTranslator()
        val dictionary = CountingDictionary()
        val converter = FakeScriptConverter()
        val session = AppSession(
            AppEnv(
                openDictionary = { dictionary },
                settingsStorage = MemorySettings,
                audio = NoAudioPlayer,
                speaker = NoSpeaker,
                clipboard = clipboard,
                translator = translator,
                links = object : LinkOpener { override fun open(url: String) {} },
            ),
            scope.backgroundScope,
        )
        val holder = ReadingStateHolder(session, converter, StandardTestDispatcher(scope.testScheduler))

        fun type(text: String) = holder.onDraftChange(TextFieldValue(text, TextRange(text.length)))
    }

    @Test
    fun theDefaultDraftIsEmpty() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        assertEquals("", f.holder.draft.text)
        assertNull(f.holder.passage)
        assertFalse(f.holder.conversion.enabled)
    }

    @Test
    fun typingAnalysesAutomaticallyAndOnlyTheLatestEdit() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.type("我")
        f.type("我们")
        f.type("我们好")
        advanceUntilIdle()
        assertEquals(listOf("我们好"), f.dictionary.analysed)
        assertEquals("我们好", f.holder.passage?.text)
        // A caret move is not an edit.
        f.holder.onDraftChange(TextFieldValue("我们好", TextRange(1)))
        advanceUntilIdle()
        assertEquals(1, f.dictionary.analysed.size)
    }

    @Test
    fun conversionNeverModifiesTheDraftAndSurvivesTyping() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.type("汉语")
        advanceUntilIdle()
        f.holder.conversion.toggle()
        advanceUntilIdle()
        assertEquals("汉语", f.holder.draft.text)
        assertEquals("漢語", f.holder.passage?.text)

        f.type("汉语汉")
        advanceUntilIdle()
        assertEquals("汉语汉", f.holder.draft.text)
        assertEquals("漢語漢", f.holder.passage?.text)
        assertEquals(ConversionAction.SHOW_ORIGINAL, f.holder.conversion.mainAction)
    }

    @Test
    fun clearPasteAndSharedTextStartANewUnconvertedPassage() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.type("汉语")
        advanceUntilIdle()
        f.holder.conversion.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW_PHRASES)
        advanceUntilIdle()
        assertTrue(f.holder.conversion.selection.isConverted)

        f.holder.clear()
        assertEquals(ConversionSelection.None, f.holder.conversion.selection)
        assertNull(f.holder.passage)                           // removed at once
        assertEquals("", f.holder.draft.text)

        f.type("汉语")
        advanceUntilIdle()
        f.holder.conversion.toggle()
        advanceUntilIdle()
        f.clipboard.text = "软件"
        assertTrue(f.holder.paste())
        assertFalse(f.holder.conversion.selection.isConverted)
        advanceUntilIdle()
        assertEquals("软件", f.holder.draft.text)
        assertEquals("软件", f.holder.passage?.text)

        f.holder.conversion.toggle()
        advanceUntilIdle()
        ReadingInbox.deliver("漢語")
        advanceUntilIdle()
        assertEquals("漢語", f.holder.draft.text)              // replaced, no merge
        assertFalse(f.holder.conversion.selection.isConverted)
        assertEquals("漢語", f.holder.passage?.text)
        assertEquals(ConversionAction.CONVERT_TO_SIMPLIFIED, f.holder.conversion.mainAction)
    }

    @Test
    fun sharedTextIsConsumedOnceAndBlankTextIsIgnored() = runTest {
        ReadingInbox.deliver("汉语")
        val f = Fixture(this)                                  // delivered before the screen existed
        advanceUntilIdle()
        assertEquals("汉语", f.holder.draft.text)
        assertNull(ReadingInbox.take())
        assertNull(ReadingInbox.pending.value)

        f.type("我")
        advanceUntilIdle()
        ReadingInbox.deliver("   \n")
        advanceUntilIdle()
        assertEquals("我", f.holder.draft.text)

        f.session.selectDestination(Destination.SEARCH)
        ReadingInbox.deliver("你好", f.session)
        advanceUntilIdle()
        assertEquals(Destination.READING, f.session.destination)
        assertEquals("你好", f.holder.draft.text)
    }

    @Test
    fun pasteWithAnEmptyClipboardIsSilentButTheShortcutReportsIt() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        assertFalse(f.holder.paste())
        assertNull(f.holder.message)

        ReadingInbox.readClipboard()
        advanceUntilIdle()
        assertEquals("Clipboard empty or unavailable", f.holder.message)

        f.clipboard.text = "你好"
        ReadingInbox.readClipboard()
        advanceUntilIdle()
        assertEquals("你好", f.holder.draft.text)
        assertNull(f.holder.message)
    }

    @Test
    fun aPassageChangeClosesTheWordSheet() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.type("汉语")
        advanceUntilIdle()
        f.holder.onPassageTap(1)
        assertEquals(1, f.holder.selectedOffset)
        f.holder.conversion.toggle()
        assertNull(f.session.wordSheet)
        assertNull(f.holder.selectedOffset)
        advanceUntilIdle()

        f.holder.onPassageTap(0)
        assertNotNull(f.session.wordSheet)
        f.type("汉语。")
        assertNull(f.session.wordSheet)
    }

    @Test
    fun sentenceActionsUseTheDisplayedText() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.type(" 汉语 ")
        advanceUntilIdle()
        f.holder.translate()
        assertEquals("汉语" to "zh-CN", f.translator.sent.last())
        f.holder.conversion.toggle()
        advanceUntilIdle()
        f.holder.copy()
        assertEquals("漢語", f.clipboard.written)
        f.holder.translate()
        assertEquals("漢語" to "zh-TW", f.translator.sent.last())

        f.session.updateSettings { it.copy(dictionaryMode = DictMode.CANTONESE) }
        advanceUntilIdle()
        f.holder.translate()
        assertEquals("yue", f.translator.sent.last().second)
    }

    @Test
    fun handwritingInsertsAtTheCaretAndDeletesBackward() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.holder.onDraftChange(TextFieldValue("我好", TextRange(1)))
        f.holder.insert("们")
        assertEquals("我们好", f.holder.draft.text)
        assertEquals(TextRange(2), f.holder.draft.selection)
        f.holder.onDraftChange(TextFieldValue("我们好", TextRange(1, 3)))
        f.holder.insert("很")                                   // replaces the selection
        assertEquals("我很", f.holder.draft.text)
        f.holder.deleteBackward()
        assertEquals("我", f.holder.draft.text)
        f.holder.onDraftChange(TextFieldValue("a𠀀", TextRange(3)))
        f.holder.deleteBackward()                              // one code point
        assertEquals("a", f.holder.draft.text)
        advanceUntilIdle()
        assertEquals("a", f.holder.passage?.text)              // edits analyse like typing
    }

    @Test
    fun translateTextIsTrimmedAndCappedAtASentenceBoundary() {
        assertEquals("你好", TranslateText.cap("  你好\n").text)
        assertFalse(TranslateText.cap("你好").truncated)
        val long = "一二三四五。" + "六".repeat(20)
        val capped = TranslateText.cap(long, maxCodePoints = 10)
        assertTrue(capped.truncated)
        assertEquals("一二三四五。", capped.text)
        assertEquals("六".repeat(10), TranslateText.cap("六".repeat(30), maxCodePoints = 10).text)
        // Code points, not UTF-16 units.
        assertEquals("𠀀".repeat(3), TranslateText.cap("𠀀".repeat(5), maxCodePoints = 3).text)
    }
}
