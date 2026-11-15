package com.szdict.app

import com.szdict.app.feature.search.SearchReselectAction
import com.szdict.app.feature.search.searchReselectAction
import com.szdict.app.integration.IncomingAction
import com.szdict.app.integration.IncomingRouter
import com.szdict.app.platform.Clipboard
import com.szdict.app.platform.LinkOpener
import com.szdict.app.platform.NoAudioPlayer
import com.szdict.app.platform.NoSpeaker
import com.szdict.app.platform.SettingsStorage
import com.szdict.app.platform.Translator
import com.szdict.core.api.FakeDictionary
import com.szdict.core.api.InputMethod
import com.szdict.core.api.Route
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationDestinationsTest {
    private fun TestScope.session(settingsIsDestination: Boolean): AppSession {
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
            voiceAvailable = !settingsIsDestination,
            settingsIsDestination = settingsIsDestination,
        )
        return AppSession(env, TestScope(UnconfinedTestDispatcher(testScheduler)))
    }

    @Test
    fun destinationsFollowThePlatformCapabilities() {
        assertEquals(
            listOf(Destination.SEARCH, Destination.OCR, Destination.VOICE, Destination.READING, Destination.HSK),
            navigationDestinations(voiceAvailable = true, settingsIsDestination = false),
        )
        assertEquals(
            listOf(Destination.SEARCH, Destination.OCR, Destination.READING, Destination.HSK, Destination.SETTINGS),
            navigationDestinations(voiceAvailable = false, settingsIsDestination = true),
        )
    }

    @Test
    fun settingsRoutesSelectTheDestinationWhereItExists() = runTest {
        val web = session(settingsIsDestination = true)
        IncomingRouter.dispatch(web, IncomingAction.Open(Route.Settings))
        assertEquals(Destination.SETTINGS, web.destination)
        assertTrue(web.routes.isEmpty())

        // Privacy is a detail route over the Settings destination; closing it returns to Settings.
        web.selectDestination(Destination.HSK)
        IncomingRouter.dispatch(web, IncomingAction.Open(Route.Privacy))
        assertEquals(Destination.SETTINGS, web.destination)
        assertTrue(web.routes.single() is DetailRoute.Privacy)
        web.openAttribution()
        assertEquals(2, web.routes.size)
        web.clearRoutes()
        assertEquals(Destination.SETTINGS, web.destination)

        // The Settings card of Search (never shown on Web) selects the destination too.
        web.selectDestination(Destination.SEARCH)
        web.openSettings()
        assertEquals(Destination.SETTINGS, web.destination)
        assertTrue(web.routes.isEmpty())

        val mobile = session(settingsIsDestination = false)
        IncomingRouter.dispatch(mobile, IncomingAction.Open(Route.Settings))
        assertEquals(Destination.SEARCH, mobile.destination)
        assertTrue(mobile.routes.single() is DetailRoute.SettingsRoute)
        IncomingRouter.dispatch(mobile, IncomingAction.Open(Route.Privacy))
        assertTrue(mobile.routes[0] is DetailRoute.SettingsRoute && mobile.routes[1] is DetailRoute.Privacy)
    }

    @Test
    fun searchRetapScrollsFirstThenActivatesTheCurrentInputMethod() {
        assertEquals(SearchReselectAction.SCROLL_TO_TOP, searchReselectAction(false, InputMethod.KEYBOARD))
        assertEquals(SearchReselectAction.SCROLL_TO_TOP, searchReselectAction(false, InputMethod.HANDWRITING))
        assertEquals(SearchReselectAction.FOCUS_KEYBOARD, searchReselectAction(true, InputMethod.KEYBOARD))
        assertEquals(SearchReselectAction.OPEN_HANDWRITING, searchReselectAction(true, InputMethod.HANDWRITING))
    }
}
