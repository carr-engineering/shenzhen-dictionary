package com.szdict.app

import androidx.compose.ui.unit.dp
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.DictMode
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.SegmentationMode
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The detail stack: a list selection replaces it, navigation inside the detail pushes (ENT-010…016). */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailStackTest {
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
                override val hasText: Flow<Boolean> = flowOf(false)
                override fun refresh() {}
                override fun readText(): String? = null
                override fun writeText(text: String) {}
            },
            translator = object : Translator { override fun translate(text: String, sourceLanguageTag: String) {} },
            links = object : LinkOpener { override fun open(url: String) {} },
        )
        return AppSession(env, TestScope(UnconfinedTestDispatcher(testScheduler)))
    }

    private fun AppSession.entryIds() = routes.map { (it as DetailRoute.Entry).entryId }

    @Test
    fun selectingInTheListReplacesTheWholeStack() = runTest {
        val session = session()
        session.openEntry(1)
        session.openEntry(2)
        session.openEntry(3)
        assertEquals(listOf(3), session.entryIds())

        // Whatever was opened from inside the detail goes with the selection it belonged to.
        session.openEntry(4, nested = true)
        session.openEntry(5, nested = true)
        assertEquals(listOf(3, 4, 5), session.entryIds())
        session.openEntry(6)
        assertEquals(listOf(6), session.entryIds())
        // Every visit has its own key (GLB-051).
        assertEquals(session.routes.map { it.key }.toSet().size, session.routes.size)
    }

    @Test
    fun navigationInsideTheDetailPushesAndPopsBackToTheSelection() = runTest {
        val session = session()
        session.openEntry(1, DictMode.CANTONESE)
        session.openEntry(2, nested = true)
        // ENT-010: a nested route inherits the mode of the route it was opened from.
        assertEquals(DictMode.CANTONESE, (session.routes.last() as DetailRoute.Entry).mode)
        // ENT-013: the entry already on top is not pushed again.
        session.openEntry(2, nested = true)
        assertEquals(listOf(1, 2), session.entryIds())
        assertTrue(session.popRoute(session.routes.last().key))
        assertEquals(listOf(1), session.entryIds())
        assertTrue(session.popRoute(session.routes.last().key))
        assertTrue(session.routes.isEmpty())
    }

    @Test
    fun selectingTheEntryOnTopKeepsItsVisitAndDropsWhatWasUnderIt() = runTest {
        val session = session()
        session.openEntry(1)
        val first = session.routes.single()
        session.openEntry(1)
        assertSame(first, session.routes.single())

        session.openEntry(2, nested = true)
        val top = session.routes.last()
        session.openEntry(2)
        assertSame(top, session.routes.single())
    }

    @Test
    fun rowsOfASheetFollowWhereTheSheetWasOpened() = runTest {
        val session = session()
        val passage = AnalysedPassage.empty("你好", DictMode.MANDARIN, SegmentationMode.HANZI, 0L)
        // A sheet over a passage of the list pane: its rows are selections.
        assertFalse(WordSheetRequest("list", passage, 0, DictMode.MANDARIN).nested)
        // A sheet opened from the entry itself: its rows go on top of that entry.
        session.openEntry(1)
        val sheet = WordSheetRequest("entry-1", passage, 0, DictMode.MANDARIN, nested = true)
        session.openWordSheet(sheet)
        session.openEntry(2, sheet.mode, nested = sheet.nested)
        assertEquals(listOf(1, 2), session.entryIds())
        assertNull(session.wordSheet)
    }

    @Test
    fun backArrowOnlyWhereThereIsSomethingToGoBackTo() = runTest {
        val session = session()
        session.openEntry(1)
        val selection = session.routes.single()
        // Single pane: always. Two panes: a lone selection has the close button alone.
        assertTrue(session.showsBack(selection, twoPane = false))
        assertFalse(session.showsBack(selection, twoPane = true))

        session.openEntry(2, nested = true)
        val nested = session.routes.last()
        assertTrue(session.showsBack(nested, twoPane = true))
        assertFalse(session.showsBack(selection, twoPane = true))

        session.popRoute(nested.key)
        assertFalse(session.showsBack(session.routes.single(), twoPane = true))

        // Settings-family routes cover the whole screen and always go back.
        session.openSettings()
        assertTrue(session.showsBack(session.routes.last(), twoPane = true))
    }

    @Test
    fun onlyNavigationInsideTheStackSlides() {
        fun entry(key: Long) = DetailRoute.Entry(key, key.toInt(), DictMode.MANDARIN, 0L)
        val a = entry(1); val b = entry(2); val c = entry(3)
        val settings = DetailRoute.SettingsRoute(9)

        // Two panes: selections and closing the last one cross-fade in place.
        assertEquals(RouteMotion.FADE, routeMotion(emptyList(), listOf(a), twoPane = true))
        assertEquals(RouteMotion.FADE, routeMotion(listOf(a), listOf(b), twoPane = true))
        assertEquals(RouteMotion.FADE, routeMotion(listOf(a, c), listOf(b), twoPane = true))
        assertEquals(RouteMotion.FADE, routeMotion(listOf(a), emptyList(), twoPane = true))
        assertEquals(RouteMotion.FADE, routeMotion(listOf(a, c), emptyList(), twoPane = true))
        // Inside the side panel the stack still slides.
        assertEquals(RouteMotion.FORWARD, routeMotion(listOf(a), listOf(a, c), twoPane = true))
        assertEquals(RouteMotion.BACK, routeMotion(listOf(a, c), listOf(a), twoPane = true))
        // Full-screen routes slide in two-pane too.
        assertEquals(RouteMotion.FORWARD, routeMotion(emptyList(), listOf(settings), twoPane = true))
        assertEquals(RouteMotion.BACK, routeMotion(listOf(settings), emptyList(), twoPane = true))
        assertEquals(RouteMotion.FORWARD, routeMotion(listOf(a), listOf(a, settings), twoPane = true))

        // Single pane is unchanged: forward in, back out.
        assertEquals(RouteMotion.FORWARD, routeMotion(emptyList(), listOf(a), twoPane = false))
        assertEquals(RouteMotion.FORWARD, routeMotion(listOf(a), listOf(a, c), twoPane = false))
        assertEquals(RouteMotion.BACK, routeMotion(listOf(a, c), listOf(a), twoPane = false))
        assertEquals(RouteMotion.BACK, routeMotion(listOf(a), emptyList(), twoPane = false))
    }

    @Test
    fun wideWindowsKeepReadablePanesAndGetTheExpandedRail() {
        assertEquals(NavigationStyle.BOTTOM_BAR, navigationStyle(599.dp))
        assertEquals(NavigationStyle.RAIL, navigationStyle(600.dp))
        assertEquals(NavigationStyle.RAIL, navigationStyle(1199.dp))
        assertEquals(NavigationStyle.EXPANDED_RAIL, navigationStyle(1200.dp))
        assertFalse(fitsTwoPanes(TwoPaneMinWidth - 1.dp))
        assertTrue(fitsTwoPanes(TwoPaneMinWidth))
        assertEquals(760.dp, TwoPaneMinWidth)
        assertFalse(fitsTwoPanes(740.dp))

        // Narrowest two-pane (a landscape phone beside the rail): the list keeps its minimum width.
        paneLayout(681.dp).let {
            assertEquals(ListPaneMinWidth, it.list)
            assertEquals(380.dp, it.detail)
            assertEquals(0.dp, it.start)
        }
        // A wider one: about 43 % list.
        paneLayout(1001.dp).let {
            assertEquals(430.dp, it.list)
            assertEquals(570.dp, it.detail)
        }
        // The list stops growing first, then the detail.
        paneLayout(1401.dp).let {
            assertEquals(ListPaneMaxWidth, it.list)
            assertEquals(800.dp, it.detail)
            assertEquals(0.dp, it.start)
        }
        // Beyond both maxima the pair is centred.
        paneLayout(2301.dp).let {
            assertEquals(ListPaneMaxWidth, it.list)
            assertEquals(ReadablePaneWidth, it.detail)
            assertEquals(400.dp, it.start)
            assertEquals(1001.dp, it.detailStart)
        }
    }
}
