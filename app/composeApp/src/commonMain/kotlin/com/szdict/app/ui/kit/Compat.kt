package com.szdict.app.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.szdict.app.AppSession
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
