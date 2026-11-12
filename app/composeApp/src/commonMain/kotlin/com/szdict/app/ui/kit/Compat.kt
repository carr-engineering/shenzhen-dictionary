package com.szdict.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
import com.szdict.core.api.InputMethod
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateCompat(): State<T> = collectAsState()

/** The app session, provided by the shell to every feature. */
val LocalSession = compositionLocalOf<AppSession> { error("AppSession not provided") }

/**
 * Extra bottom inset every scrolling surface must add to its content padding: the visible height of
 * the word sheet or handwriting sheet (TXT-044, HW-021). Zero when neither is visible.
 */
val LocalSheetInset = compositionLocalOf { 0.dp }

@Composable
fun sheetInset(): Dp = LocalSheetInset.current

/**
 * Wraps a text field so no platform IME session starts — and so no soft keyboard shows — while
 * handwriting is the current input method (HW-004, HW-021). The field keeps focus, caret and
 * selection. The method is read from the live setting when a session is requested, not from the
 * last composition: the input-method button switches the method and focuses the field in the same
 * frame, and a session started in between would bring the keyboard up next to the handwriting
 * sheet. A new interceptor per method restarts a running session when the method changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InputMethodGate(content: @Composable () -> Unit) {
    val session = LocalSession.current
    val settings by session.settings.collectAsState()
    val interceptor = remember(session, settings.inputMethod) {
        PlatformTextInputInterceptor { request, next ->
            if (session.settings.value.inputMethod == InputMethod.HANDWRITING) awaitCancellation() else next.startInputMethod(request)
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Whether the soft keyboard is showing (the bottom bar hides under it). */
@Composable
expect fun imeVisible(): Boolean
