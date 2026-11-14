package com.szdict.app.feature.voice

import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.feature.conversion.ConversionAction
import com.szdict.app.feature.conversion.FakeScriptConverter
import com.szdict.app.feature.conversion.ScriptVariant
import com.szdict.app.feature.conversion.settle
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.Dictionary
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.Script
import com.szdict.core.api.SegmentationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceStateHolderTest {
    /** Records analyses as they start and as they complete; [gate] holds them in between. */
    private class GatedDictionary(private val inner: Dictionary = FakeDictionary()) : Dictionary by inner {
        val started = ArrayList<String>()
        val completed = ArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun analyse(text: String, segmentation: SegmentationMode): AnalysedPassage {
            started += text
            gate?.await()
            if (fail) throw IllegalStateException("analysis failed")
            return inner.analyse(text, segmentation).also { completed += text }
        }
    }

    private class FakeClipboard : Clipboard {
        var written: String? = null
        override val hasText: Flow<Boolean> = MutableStateFlow(false)
        override fun refresh() {}
        override fun readText(): String? = null
        override fun writeText(text: String) { written = text }
    }

    private object MemorySettings : SettingsStorage {
        override val values: Flow<Map<String, String>> = flowOf(emptyMap())
        override suspend fun update(changes: Map<String, String?>) {}
        override fun flushBlocking(changes: Map<String, String?>) {}
    }

    private class Fixture(private val scope: TestScope, pack: VoicePackState = VoicePackState.Present) {
        val capture = FakeAudioCapture()
        val recognizer = ScriptedSpeechRecognizer()
        val pack = FakeVoiceModelPack(pack)
        val permission = FakeMicPermission()
        val foreground = FakeForegroundMonitor()
        val dictionary = GatedDictionary()
        val converter = FakeScriptConverter()
        val clipboard = FakeClipboard()
        val services = VoiceServices(capture, recognizer, this.pack, permission, foreground)
        val session = AppSession(
            AppEnv(
                openDictionary = { dictionary },
                settingsStorage = MemorySettings,
                audio = NoAudioPlayer,
                speaker = NoSpeaker,
                clipboard = clipboard,
                translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
                links = object : LinkOpener { override fun open(url: String) {} },
                services = mapOf(VoiceServices.KEY to services),
            ),
            scope.backgroundScope,
        )
        val holder = VoiceStateHolder(session, converter = converter, analysisContext = StandardTestDispatcher(scope.testScheduler))

        init {
            scope.settle()   // stored settings are loaded before the test changes any
        }

        /** Opens the destination and starts listening. */
        fun listen() {
            holder.enter()
            scope.settle()
            holder.startListening()
            scope.settle()
            assertEquals(VoicePhase.LISTENING, holder.phase)
        }

        fun say(text: String) {
            recognizer.say(text)
            scope.settle()
        }

        fun endpoint() {
            recognizer.endpoint()
            scope.settle()
        }

        fun commit(text: String) {
            say(text)
            endpoint()
        }

        fun traditional() {
            session.updateSettings { it.withScript(DictMode.MANDARIN, Script.TRADITIONAL) }
            scope.settle()
        }
    }

    // ---- destination, permission, model ----

    @Test
    fun openingLoadsTheModelAndNeverStartsListening() = runTest {
        val f = Fixture(this)
        settle()
        assertEquals(0, f.recognizer.loads)
        f.holder.enter()
        settle()
        assertEquals(VoiceModelState.READY, f.holder.modelState)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertEquals(0, f.capture.opens)
        assertEquals(0, f.permission.requests)   // VOICE-010: never on merely opening
        assertTrue(f.holder.isEmpty)
        assertNull(f.holder.status)
    }

    @Test
    fun thePermissionIsRequestedOnTheFirstStart() = runTest {
        val f = Fixture(this)
        f.permission.current = MicPermissionStatus.NOT_REQUESTED
        f.holder.enter()
        settle()
        f.holder.startListening()
        settle()
        assertEquals(1, f.permission.requests)
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
        assertNull(f.holder.status)
        assertTrue(f.capture.open)
    }

    @Test
    fun theElapsedTimeStartsAtZeroOnEveryStart() = runTest {
        val f = Fixture(this)
        f.holder.enter()
        settle()
        assertEquals(0, f.holder.elapsedSeconds)
        f.holder.startListening()
        settle()
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
        assertEquals(0, f.holder.elapsedSeconds)
        f.holder.stopListening()
        settle()
        f.holder.startListening()
        settle()
        assertEquals(0, f.holder.elapsedSeconds)
        f.holder.stopListening()
        settle()
    }

    @Test
    fun elapsedTimeIsFormattedAsMinutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:07", formatElapsed(7))
        assertEquals("1:00", formatElapsed(60))
        assertEquals("12:05", formatElapsed(725))
        assertEquals("61:01", formatElapsed(3661))
        assertEquals("0:00", formatElapsed(-3))
    }

    @Test
    fun aDenialShowsTheRationaleAndNeverOpensTheMicrophone() = runTest {
        val f = Fixture(this)
        f.permission.current = MicPermissionStatus.NOT_REQUESTED
        f.permission.answer = MicPermissionStatus.DENIED
        f.holder.enter()
        settle()
        f.holder.startListening()
        settle()
        assertEquals(MicPermissionStatus.DENIED, f.holder.permissionStatus)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertEquals(0, f.capture.opens)

        // `Try again` asks again; a permanent denial offers the settings instead.
        f.permission.answer = MicPermissionStatus.DENIED_PERMANENTLY
        f.holder.startListening()
        settle()
        assertEquals(2, f.permission.requests)
        assertEquals(MicPermissionStatus.DENIED_PERMANENTLY, f.holder.permissionStatus)
        f.holder.openPermissionSettings()
        assertEquals(1, f.permission.settingsOpened)

        // Granted in the system settings: re-checked on resume (VOICE-011).
        f.permission.current = MicPermissionStatus.GRANTED
        f.foreground.state.value = false
        settle()
        f.foreground.state.value = true
        settle()
        assertEquals(MicPermissionStatus.GRANTED, f.holder.permissionStatus)
        f.holder.startListening()
        settle()
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
    }

    @Test
    fun audioIsBufferedWhileTheModelLoads() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.recognizer.loadGate = gate
        f.holder.enter()
        settle()
        assertEquals(VoiceModelState.LOADING, f.holder.modelState)
        f.holder.startListening()
        settle()
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
        assertEquals("Preparing…", f.holder.status)
        repeat(5) { f.capture.send() }
        settle()
        assertEquals(0, f.recognizer.framesHeard)
        assertTrue(f.holder.inputLevel > 0f)

        gate.complete(Unit)
        settle()
        assertNull(f.holder.status)
        assertEquals(5, f.recognizer.framesHeard)   // VOICE-022: recognised once the model is ready
    }

    @Test
    fun aMissingPackDisablesStartAndIsRequestedOnFirstOpen() = runTest {
        val f = Fixture(this, pack = VoicePackState.Missing(240_000_000))
        settle()
        assertEquals(0, f.pack.requests)
        f.holder.enter()
        settle()
        assertEquals(1, f.pack.requests)
        assertFalse(f.holder.canStart)
        f.holder.startListening()
        settle()
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertEquals(0, f.recognizer.loads)

        f.pack.current.value = VoicePackState.Downloading(0.45f)
        settle()
        assertEquals(VoicePackState.Downloading(0.45f), f.holder.packState)
        f.pack.current.value = VoicePackState.Present
        settle()
        assertTrue(f.holder.canStart)
        assertEquals(VoiceModelState.READY, f.holder.modelState)
    }

    // ---- transcript ----

    @Test
    fun theLiveUtteranceIsResegmentedOnEveryUpdateAndFrozenOnCommit() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        val first = assertNotNull(f.holder.live)
        assertEquals("你好", first.display)
        assertEquals("你好", first.passage?.text)
        assertTrue(first.settled)

        f.say("你好中国")
        val second = assertNotNull(f.holder.live)
        assertEquals(first.id, second.id)
        assertEquals("你好中国", second.passage?.text)
        assertEquals(listOf(0, 1, 2, 3), second.passage?.coveredOffsets)
        assertEquals(listOf("你好", "你好中国"), f.dictionary.completed)

        f.endpoint()
        assertNull(f.holder.live)
        assertEquals(1, f.holder.utterances.size)
        // The live analysis already covered the utterance: committing analyses nothing again (VOICE-032).
        assertSame(second.passage, f.holder.utterances[0].passage)
        assertEquals(listOf("你好", "你好中国"), f.dictionary.completed)

        f.say("中国")
        assertEquals("中国", f.holder.live?.display)
        assertEquals(1, f.holder.utterances.size)
        assertEquals("你好中国\n中国", f.holder.transcriptText())
    }

    @Test
    fun newCharactersShowAtOnceOnThePreviousAnalysis() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        val gate = CompletableDeferred<Unit>()
        f.dictionary.gate = gate
        f.say("你好中国")
        // GLB-030: the previous analysis stays on its prefix; the new characters are unmatched.
        val pending = assertNotNull(f.holder.live)
        assertEquals("你好中国", pending.display)
        assertFalse(pending.settled)
        val passage = assertNotNull(pending.passage)
        assertEquals("你好中国", passage.text)
        assertTrue(passage.segments.all { it.end <= 2 })
        assertEquals(listOf(0, 1), passage.coveredOffsets)

        gate.complete(Unit)
        settle()
        assertTrue(f.holder.live!!.settled)
        assertEquals(listOf(0, 1, 2, 3), f.holder.live!!.passage?.coveredOffsets)
    }

    @Test
    fun liveAnalysesAreLatestOnly() = runTest {
        val f = Fixture(this)
        f.listen()
        val gate = CompletableDeferred<Unit>()
        f.dictionary.gate = gate
        f.say("你")
        f.say("你好")
        f.say("你好中")
        f.say("你好中国")
        assertEquals("你好中国", f.holder.live?.display)   // shown immediately
        assertTrue(f.dictionary.completed.isEmpty())

        gate.complete(Unit)
        settle()
        // Superseded analyses were cancelled: only the newest one completes and is applied.
        assertEquals(listOf("你好中国"), f.dictionary.completed)
        assertEquals("你好中国", f.holder.live?.passage?.text)
        assertTrue(f.holder.live!!.settled)
    }

    @Test
    fun aCommitIsAnalysedEvenWhileLiveUpdatesKeepComing() = runTest {
        val f = Fixture(this)
        f.listen()
        val gate = CompletableDeferred<Unit>()
        f.dictionary.gate = gate
        f.commit("你好")
        f.say("中")
        f.say("中国")
        assertFalse(f.holder.utterances[0].settled)

        gate.complete(Unit)
        settle()
        // VOICE-080: the committed utterance's analysis is never superseded by live updates.
        assertTrue(f.holder.utterances[0].settled)
        assertEquals("你好", f.holder.utterances[0].passage?.text)
        assertTrue("你好" in f.dictionary.completed)
        assertEquals("中国", f.holder.live?.passage?.text)
    }

    @Test
    fun committedUtterancesNeverChangeAsTheTranscriptGrows() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("你好")
        f.commit("中国")
        val frozen = f.holder.utterances.toList()
        f.say("我")
        f.say("我们")
        f.commit("我们好")
        f.say("你")

        assertEquals(3, f.holder.utterances.size)
        assertSame(frozen[0], f.holder.utterances[0])
        assertSame(frozen[1], f.holder.utterances[1])
        assertEquals(listOf("你好", "中国", "我们好"), f.holder.utterances.map { it.display })
        // Each utterance was analysed exactly once after it stopped changing.
        assertEquals(1, f.dictionary.completed.count { it == "你好" })
        assertEquals(1, f.dictionary.completed.count { it == "中国" })
    }

    @Test
    fun silenceProducesNoUtterance() = runTest {
        val f = Fixture(this)
        f.listen()
        f.endpoint()
        f.endpoint()
        assertTrue(f.holder.isEmpty)
        f.holder.stopListening()
        settle()
        assertTrue(f.holder.isEmpty)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
    }

    // ---- word sheet ----

    @Test
    fun theSheetStaysOpenWhileTheLiveUtteranceGrowsAndCommits() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("你好")
        f.say("中国")
        val live = f.holder.live!!
        f.holder.onTap(live.id, 1)
        val opened = assertNotNull(f.session.wordSheet)
        assertEquals("你好\n中国", opened.passage.text)
        assertEquals(4, opened.offset)
        assertEquals(1, f.holder.selectedOffset(live))

        f.say("中国人")
        val grown = assertNotNull(f.session.wordSheet)   // VOICE-041: refreshed in place, not closed
        assertEquals("你好\n中国人", grown.passage.text)
        assertEquals(4, grown.offset)

        f.endpoint()   // VOICE-094: survives the commit and the next utterance's start
        f.say("我")
        val later = assertNotNull(f.session.wordSheet)
        assertEquals("你好\n中国人\n我", later.passage.text)
        assertEquals(4, later.offset)
        assertEquals(1, f.holder.selectedOffset(f.holder.utterances[1]))
        assertNull(f.holder.selectedOffset(f.holder.utterances[0]))
    }

    @Test
    fun aSelectionInACommittedUtteranceKeepsItsOffset() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("你好")
        f.holder.onTap(f.holder.utterances[0].id, 1)
        repeat(3) { f.commit("中国") }
        f.say("我们")
        val sheet = assertNotNull(f.session.wordSheet)
        assertEquals(1, sheet.offset)   // VOICE-031
        assertTrue(sheet.passage.text.startsWith("你好\n"))
        assertEquals('好', sheet.passage.text[sheet.offset])
    }

    @Test
    fun steppingCrossesUtterances() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("你好")
        f.commit("hello")   // VOICE-091: nothing to step onto
        f.commit("中国")
        f.holder.onTap(f.holder.utterances[0].id, 1)
        val sheet = assertNotNull(f.session.wordSheet)
        assertEquals("你好\nhello\n中国", sheet.passage.text)
        assertTrue(f.holder.utterances[1].passage!!.coveredOffsets.isEmpty())
        val next = assertNotNull(sheet.passage.nextCovered(sheet.offset))
        assertEquals('中', sheet.passage.text[next])

        f.session.moveWordSheet(next)   // VOICE-042
        assertEquals(0, f.holder.selectedOffset(f.holder.utterances[2]))
        assertNull(f.holder.selectedOffset(f.holder.utterances[0]))
        f.say("我")
        assertEquals(next, f.session.wordSheet?.offset)
    }

    // ---- actions ----

    @Test
    fun clearEmptiesTheTranscriptAndListeningContinuesFresh() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("你好")
        f.say("中国")
        f.holder.onTap(f.holder.utterances[0].id, 0)
        f.holder.clear()
        settle()
        assertTrue(f.holder.isEmpty)
        assertNull(f.session.wordSheet)
        assertEquals(VoicePhase.LISTENING, f.holder.phase)

        // The recogniser is still inside the same utterance: only what follows Clear is shown.
        f.say("中国人")
        assertEquals("人", f.holder.live?.display)
        f.endpoint()
        f.say("你好")
        assertEquals(listOf("人"), f.holder.utterances.map { it.display })
        assertEquals("你好", f.holder.live?.display)
    }

    @Test
    fun copyTakesTheDisplayedTranscriptIncludingTheLiveLine() = runTest {
        val f = Fixture(this)
        f.traditional()
        f.listen()
        f.commit("汉语")
        f.say("你好")
        f.holder.copy()
        assertEquals("漢語\n你好", f.clipboard.written)
    }

    // ---- script conversion (VOICE-096) ----

    @Test
    fun aSimplifiedScriptShowsTheOriginal() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("汉语")
        assertEquals("汉语", f.holder.live?.display)
        assertTrue(f.converter.conversions.isEmpty())
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, f.holder.selection.mainAction(f.session.script, f.session.mode))

        f.holder.toggleConversion()
        settle()
        assertEquals("漢語", f.holder.live?.display)
        assertEquals("漢語", f.holder.live?.passage?.text)
    }

    @Test
    fun aTraditionalScriptConvertsByDefault() = runTest {
        val f = Fixture(this)
        f.traditional()
        f.listen()
        f.say("汉语")
        // Mandarin mode converts with OpenCC standard; analysis runs on the displayed text.
        assertEquals("漢語", f.holder.live?.display)
        assertEquals("漢語", f.holder.live?.passage?.text)
        assertEquals(ScriptVariant.TRADITIONAL, f.holder.selection.applied?.target)
        assertEquals(ConversionAction.SHOW_ORIGINAL, f.holder.selection.mainAction(f.session.script, f.session.mode))
        f.endpoint()
        f.say("语")
        assertEquals("汉语", f.holder.utterances[0].source)
        assertEquals("漢語", f.holder.utterances[0].display)

        // `Show original` converts every utterance back, the live one included, and closes the sheet.
        f.holder.onTap(f.holder.utterances[0].id, 0)
        assertNotNull(f.session.wordSheet)
        f.holder.toggleConversion()
        settle()
        assertNull(f.session.wordSheet)
        assertEquals("汉语", f.holder.utterances[0].display)
        assertEquals("汉语", f.holder.utterances[0].passage?.text)
        assertEquals("语", f.holder.live?.display)
        // Offered again as a conversion from Simplified, which is what the recogniser writes.
        assertEquals(ConversionAction.CONVERT_TO_TRADITIONAL, f.holder.selection.mainAction(f.session.script, f.session.mode))

        // The choice holds until Clear; then the default applies again.
        f.endpoint()
        f.commit("汉")
        assertEquals("汉", f.holder.utterances.last().display)
        f.holder.clear()
        f.commit("汉")
        assertEquals("漢", f.holder.utterances.single().display)
    }

    @Test
    fun cantoneseModeConvertsToHongKongTraditional() = runTest {
        val f = Fixture(this)
        f.session.updateSettings { it.copy(dictionaryMode = DictMode.CANTONESE) }
        settle()
        assertEquals(Script.TRADITIONAL, f.session.script)
        assertEquals(ScriptVariant.TRADITIONAL_HK, f.holder.selection.applied?.target)
    }

    @Test
    fun aScriptChangeReappliesTheDefaultAndReanalysesEverything() = runTest {
        val f = Fixture(this)
        f.listen()
        f.commit("汉语")
        f.say("语")
        f.traditional()
        assertEquals(VoicePhase.LISTENING, f.holder.phase)   // VOICE-092
        assertEquals("漢語", f.holder.utterances[0].display)
        assertEquals("漢語", f.holder.utterances[0].passage?.text)
        assertEquals("語", f.holder.live?.display)

        // After an explicit choice a script change no longer overrides it.
        f.holder.toggleConversion()
        settle()
        assertEquals("汉语", f.holder.utterances[0].display)
        f.session.updateSettings { it.withScript(DictMode.MANDARIN, Script.SIMPLIFIED) }
        settle()
        f.session.updateSettings { it.withScript(DictMode.MANDARIN, Script.TRADITIONAL) }
        settle()
        assertEquals("汉语", f.holder.utterances[0].display)
    }

    @Test
    fun aLengthChangingConversionClearsAMovedSelection() = runTest {
        val f = Fixture(this)
        f.listen()
        f.holder.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW_PHRASES)   // nothing to convert yet
        f.say("软")
        f.holder.convert(ScriptVariant.SIMPLIFIED, ScriptVariant.TRADITIONAL_TW_PHRASES)
        settle()
        f.say("软件好")
        assertEquals("軟體好", f.holder.live?.display)
        f.holder.onTap(f.holder.live!!.id, 2)
        assertNotNull(f.session.wordSheet)
        // Same length, same character at the offset: the sheet stays.
        f.say("软件好汉")
        assertNotNull(f.session.wordSheet)
        assertEquals(2, f.holder.selectedOffset(f.holder.live!!))
    }

    @Test
    fun aFailedConversionKeepsTheOriginalOnScreen() = runTest {
        val f = Fixture(this)
        f.traditional()
        f.converter.fail = true
        f.listen()
        f.commit("汉语")
        assertEquals("汉语", f.holder.utterances[0].display)
        assertEquals("汉语", f.holder.utterances[0].passage?.text)
    }

    // ---- lifecycle ----

    @Test
    fun stoppingCommitsTheLiveUtteranceAndStartingAgainAppends() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        f.holder.stopListening()
        settle()
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertFalse(f.capture.open)
        assertNull(f.holder.live)
        assertEquals(listOf("你好"), f.holder.utterances.map { it.display })
        assertEquals(VoiceModelState.READY, f.holder.modelState)   // still on the destination

        f.holder.startListening()   // VOICE-093
        settle()
        f.commit("中国")
        assertEquals(listOf("你好", "中国"), f.holder.utterances.map { it.display })
        assertEquals(2, f.recognizer.sessions)
    }

    @Test
    fun leavingStopsTheMicrophoneCommitsAndReleasesTheModel() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        f.holder.leave()
        settle()
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertFalse(f.capture.open)
        assertEquals(0, f.recognizer.activeSessions)
        assertFalse(f.recognizer.loaded)
        assertEquals(1, f.recognizer.releases)
        assertEquals(VoiceModelState.UNLOADED, f.holder.modelState)
        // VOICE-060: the transcript is kept, with the live utterance committed.
        assertEquals(listOf("你好"), f.holder.utterances.map { it.display })

        // VOICE-061: returning reloads the model but does not restart listening.
        f.holder.enter()
        settle()
        assertEquals(VoiceModelState.READY, f.holder.modelState)
        assertEquals(2, f.recognizer.loads)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertEquals(1, f.capture.opens)
    }

    @Test
    fun leavingWhileIdleReleasesTheModel() = runTest {
        val f = Fixture(this)
        f.holder.enter()
        settle()
        f.holder.leave()
        settle()
        assertFalse(f.recognizer.loaded)
        assertEquals(VoiceModelState.UNLOADED, f.holder.modelState)
    }

    @Test
    fun backgroundingStopsListeningAndReturningReloadsWithoutListening() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        f.foreground.state.value = false   // VOICE-062
        settle()
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertFalse(f.capture.open)
        assertFalse(f.recognizer.loaded)
        assertEquals(listOf("你好"), f.holder.utterances.map { it.display })

        f.foreground.state.value = true
        settle()
        assertEquals(VoiceModelState.READY, f.holder.modelState)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
    }

    @Test
    fun anAudioInterruptionStopsListeningAndCommits() = runTest {
        val f = Fixture(this)
        f.listen()
        f.say("你好")
        f.capture.interrupt()
        settle()
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertNull(f.holder.error)
        assertEquals(listOf("你好"), f.holder.utterances.map { it.display })
    }

    // ---- errors are recoverable without a restart (ARCH-122) ----

    @Test
    fun anUnavailableMicrophoneIsReportedAndRecoverable() = runTest {
        val f = Fixture(this)
        f.capture.fail = true
        f.holder.enter()
        settle()
        f.holder.startListening()
        settle()
        assertEquals(VoiceError.MIC_UNAVAILABLE, f.holder.error)   // VOICE-012
        assertEquals("The microphone is unavailable right now.", f.holder.status)
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertEquals(0, f.recognizer.activeSessions)

        f.capture.fail = false
        f.holder.startListening()
        settle()
        assertNull(f.holder.error)
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
        f.commit("你好")
        assertEquals(1, f.holder.utterances.size)
    }

    @Test
    fun aModelThatFailsToLoadIsReportedAndRecoverable() = runTest {
        val f = Fixture(this)
        f.recognizer.failLoad = true
        f.holder.enter()
        settle()
        assertEquals(VoiceModelState.FAILED, f.holder.modelState)
        f.holder.startListening()
        settle()
        assertEquals(VoiceError.MODEL_UNAVAILABLE, f.holder.error)   // VOICE-023
        assertEquals(VoicePhase.IDLE, f.holder.phase)
        assertFalse(f.capture.open)

        f.recognizer.failLoad = false
        f.holder.startListening()
        settle()
        assertNull(f.holder.error)
        assertEquals(VoiceModelState.READY, f.holder.modelState)
        assertEquals(VoicePhase.LISTENING, f.holder.phase)
    }

    @Test
    fun aFailedAnalysisLeavesTheUtteranceReadable() = runTest {
        val f = Fixture(this)
        f.listen()
        f.dictionary.fail = true
        f.commit("你好")
        assertEquals("你好", f.holder.utterances[0].display)
        assertNull(f.holder.utterances[0].passage)
        // The next re-analysis (here: a script change) recovers it.
        f.dictionary.fail = false
        f.traditional()
        assertEquals("你好", f.holder.utterances[0].passage?.text)
    }

    @Test
    fun withoutVoiceServicesTheDestinationIsUnavailable() = runTest {
        val session = AppSession(
            AppEnv(
                openDictionary = { FakeDictionary() },
                settingsStorage = MemorySettings,
                audio = NoAudioPlayer,
                speaker = NoSpeaker,
                clipboard = FakeClipboard(),
                translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
                links = object : LinkOpener { override fun open(url: String) {} },
            ),
            backgroundScope,
        )
        val holder = VoiceStateHolder(session, converter = FakeScriptConverter())
        holder.enter()
        holder.startListening()
        settle()
        assertFalse(holder.available)
        assertFalse(holder.canStart)
        assertEquals(VoicePhase.IDLE, holder.phase)
    }

    // ---- audio helpers ----

    @Test
    fun theResamplerKeepsDurationAndLevelAcrossFrames() {
        val resampler = Resampler(48_000)
        var produced = 0
        var last = 0f
        repeat(10) {
            val out = resampler.resample(FloatArray(2_400 + it) { 0.5f })   // uneven frames
            produced += out.size
            if (out.isNotEmpty()) last = out.last()
        }
        val consumed = (0 until 10).sumOf { 2_400 + it }
        assertTrue(kotlin.math.abs(produced - consumed / 3) <= 1, "produced $produced of ${consumed / 3}")
        assertEquals(0.5f, last, 1e-6f)

        val odd = Resampler(44_100)
        val total = (0 until 20).sumOf { odd.resample(FloatArray(2_205)).size }
        assertTrue(kotlin.math.abs(total - 16_000) <= 1, "one second in, one second out: $total")
    }

    @Test
    fun theInputLevelFollowsLoudness() {
        assertEquals(0f, VoiceStateHolder.levelOf(FloatArray(800)))
        val quiet = VoiceStateHolder.levelOf(FloatArray(800) { 0.01f })
        val loud = VoiceStateHolder.levelOf(FloatArray(800) { 0.5f })
        assertTrue(quiet in 0f..loud && loud <= 1f && loud > 0.8f)
    }

    @Test
    fun downloadSizesAreReadable() {
        assertEquals("240 MB", formatSize(240_000_000))
        assertEquals("1.2 GB", formatSize(1_250_000_000))
    }
}
