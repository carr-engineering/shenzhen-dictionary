package com.szdict.app.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.szdict.app.AppSession
import com.szdict.app.DetailRoute

/** Placeholder — replaced by the feature implementation. The signature is the contract with the app shell. */
@Composable
fun PrivacyScreen(session: AppSession, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("PrivacyScreen") }
}
