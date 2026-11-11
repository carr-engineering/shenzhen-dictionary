package com.szdict.app.feature.settings

import com.szdict.app.AppEnv
import com.szdict.app.AppSession
import com.szdict.app.Destination
import com.szdict.app.DetailRoute
import com.szdict.app.feature.hsk.HskStates
import com.szdict.app.feature.reading.ReadingInbox
import com.szdict.app.integration.IncomingAction
import com.szdict.app.integration.IncomingIntentData
import com.szdict.app.integration.IncomingIntents
import com.szdict.app.integration.IncomingRouter
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.DictMode
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.Route
import com.szdict.core.api.SearchSide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Share targets, shortcuts and links (SET-050…056, SET-064, GLB-110…112). */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingRouterTest {
    private fun send(type: String, text: String? = null, stream: String? = null) =
        IncomingIntentData(IncomingRouter.ACTION_SEND, type = type, text = text, streamUri = stream)

    private fun view(url: String, widget: Boolean = false) = IncomingIntentData(IncomingRouter.ACTION_VIEW, data = url, fromWidget = widget)

    @Test
    fun sharedImageGoesToOcrAndSharedTextToReading() {
        assertEquals(IncomingAction.ScanImage("content://media/1"), IncomingRouter.classify(send("image/png", stream = "content://media/1")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(send("image/png")))
        assertEquals(IncomingAction.ReadText("你好"), IncomingRouter.classify(send("text/plain", text = "你好")))
        assertEquals(IncomingAction.ReadText(""), IncomingRouter.classify(send("text/plain")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(send("application/pdf", stream = "content://x")))
        assertEquals(
            IncomingAction.ReadText("学习"),
            IncomingRouter.classify(IncomingIntentData(IncomingRouter.ACTION_PROCESS_TEXT, type = "text/plain", processText = "学习")),
        )
    }

    @Test
    fun shortcutsAndLinksResolveThroughTheRouteTable() {
        assertEquals(IncomingAction.ReadClipboard, IncomingRouter.classify(IncomingIntentData(IncomingRouter.ACTION_READ_CLIPBOARD)))
        assertEquals(IncomingAction.Open(Route.Ocr), IncomingRouter.classify(view("https://szdict.com/ocr")))
        assertEquals(IncomingAction.Open(Route.Voice), IncomingRouter.classify(view("https://szdict.com/voice")))
        assertEquals(IncomingAction.Open(Route.Home(null)), IncomingRouter.classify(view("https://szdict.com/voice"), voiceAvailable = false))
        assertEquals(
            IncomingAction.Open(Route.Word(DictMode.CANTONESE, "0123456789abcdef"), fromWidget = true),
            IncomingRouter.classify(view("https://szdict.com/yue/word/0123456789abcdef", widget = true)),
        )
        assertEquals(
            IncomingAction.Open(Route.Search(DictMode.MANDARIN, "ni hao", SearchSide.CHINESE)),
            IncomingRouter.classify(view("https://szdict.com/zh/search?q=ni%20hao&side=zh")),
        )
        assertEquals(IncomingAction.Open(Route.Hsk(DictMode.MANDARIN, 3)), IncomingRouter.classify(view("https://szdict.com/zh/hsk/3")))
        assertEquals(IncomingAction.Open(Route.Privacy), IncomingRouter.classify(view("https://SZDICT.com/privacy")))
        // GLB-112: unknown paths fall back to Search; other hosts and schemes are not ours.
        assertEquals(IncomingAction.Open(Route.Home(null)), IncomingRouter.classify(view("https://szdict.com/nope/at/all")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(view("https://example.com/zh/word/0123456789abcdef")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(view("http://szdict.com/ocr")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(view("https://szdict.com.evil.example/ocr")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(IncomingIntentData("android.intent.action.MAIN")))
        assertEquals(IncomingAction.None, IncomingRouter.classify(IncomingIntentData(null)))
    }

    private fun TestScope.session(): AppSession {
        val env = AppEnv(
            openDictionary = { FakeDictionary(it) },
            settingsStorage = object : SettingsStorage {
                override val values: Flow<Map<String, String>> = flowOf(emptyMap())
                override suspend fun update(changes: Map<String, String?>) {}
                override fun flushBlocking(changes: Map<String, String?>) {}
            },
            audio = NoAudioPlayer,
            speaker = NoSpeaker,
            clipboard = object : Clipboard {
                override val hasText: Flow<Boolean> = flowOf(true)
                override fun refresh() {}
                override fun readText(): String? = "剪贴板"
                override fun writeText(text: String) {}
            },
            translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
            links = object : LinkOpener { override fun open(url: String) {} },
        )
        return AppSession(env, TestScope(UnconfinedTestDispatcher(testScheduler)))
    }

    private fun drain() {
        ReadingInbox.take(); IncomingIntents.consumeImageUri(); IncomingIntents.consumeRoute()
    }

    @Test
    fun sharedContentIsDeliveredOnceToItsDestination() = runTest {
        drain()
        val session = session()
        assertFalse(IncomingRouter.dispatch(session, IncomingAction.ReadText("你好世界")))
        assertEquals(Destination.READING, session.destination)
        assertEquals("你好世界", (ReadingInbox.take() as ReadingInbox.Delivery.SharedText).text)
        assertNull(ReadingInbox.take())   // SET-055: consumed once

        // SET-056: blank text opens Reading with the draft unchanged.
        session.selectDestination(Destination.SEARCH)
        IncomingRouter.dispatch(session, IncomingAction.ReadText("  "))
        assertEquals(Destination.READING, session.destination)
        assertNull(ReadingInbox.take())

        IncomingRouter.dispatch(session, IncomingAction.ScanImage("content://media/7"))
        assertEquals(Destination.OCR, session.destination)
        assertEquals("content://media/7", IncomingIntents.consumeImageUri())
        assertNull(IncomingIntents.consumeImageUri())

        // SET-051: the clipboard shortcut defers its read until the window has focus.
        assertTrue(IncomingRouter.dispatch(session, IncomingAction.ReadClipboard))
        assertEquals(Destination.READING, session.destination)
        assertNull(ReadingInbox.take())
        IncomingRouter.readClipboardNow()
        assertEquals(ReadingInbox.Delivery.ReadClipboard, ReadingInbox.take())
    }

    @Test
    fun wordLinkOpensTheEntryInTheLinksModeWithoutChangingTheSetting() = runTest {
        drain()
        val session = session()
        val key = 1.toString(16).padStart(16, '0')
        session.selectDestination(Destination.HSK)
        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Word(DictMode.CANTONESE, key), fromWidget = true))
        assertEquals(Destination.SEARCH, session.destination)
        val route = session.routes.single() as DetailRoute.Entry
        assertEquals(1, route.entryId)
        assertEquals(DictMode.CANTONESE, route.mode)
        assertEquals(DictMode.MANDARIN, session.mode)   // GLB-110: route-scoped, never persisted

        // GLB-112: an unknown key from a link falls back to Search; from the widget it shows the entry error state (SET-083).
        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Word(DictMode.MANDARIN, "ffffffffffffffff")))
        assertTrue(session.routes.isEmpty())
        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Word(DictMode.MANDARIN, "ffffffffffffffff"), fromWidget = true))
        assertEquals(IncomingRouter.UNKNOWN_ENTRY_ID, (session.routes.single() as DetailRoute.Entry).entryId)
    }

    @Test
    fun destinationAndPageRoutesAreApplied() = runTest {
        drain()
        val session = session()
        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Privacy))
        assertEquals(Destination.SEARCH, session.destination)
        assertTrue(session.routes[0] is DetailRoute.SettingsRoute && session.routes[1] is DetailRoute.Privacy)

        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Ocr))
        assertEquals(Destination.OCR, session.destination)
        assertTrue(session.routes.isEmpty())
        assertNull(IncomingIntents.consumeRouteIf { it is Route.Search })
        assertEquals(Route.Ocr, IncomingIntents.consumeRouteIf { it == Route.Ocr })

        val search = Route.Search(DictMode.CANTONESE, "nei5 hou2", null)
        IncomingRouter.dispatch(session, IncomingAction.Open(search))
        assertEquals(Destination.SEARCH, session.destination)
        assertEquals(search, IncomingIntents.consumeRoute())

        IncomingRouter.dispatch(session, IncomingAction.Open(Route.Hsk(DictMode.CANTONESE, 4)))
        assertEquals(Destination.HSK, session.destination)
        val hsk = HskStates.of(session)
        assertEquals(DictMode.CANTONESE, hsk.linkMode)
        assertEquals(4, hsk.pendingLevel)
        // Leaving the destination ends the link mode.
        session.selectDestination(Destination.SEARCH)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        testScheduler.runCurrent()
        assertNull(hsk.linkMode)
        assertEquals(DictMode.MANDARIN, session.mode)
    }
}
