package com.szdict.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.szdict.app.AppSession
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette
import com.szdict.core.api.DatasetInfo

/**
 * About & Attribution (SET-004, SET-040…044): one card per bundled data source — with the version
 * recorded in the installed dataset — then the models and engines, the library licences and the
 * privacy policy. Links open in the platform browser.
 */
@Composable
fun AttributionScreen(session: AppSession, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val settings by session.settings.collectAsState()
    val mode = settings.dictionaryMode
    var showLicences by rememberSaveable { mutableStateOf(false) }
    val info by produceState<DatasetInfo?>(null, mode, session.dataGeneration) {
        value = runCatching { session.dictionary(mode).info() }.getOrNull()
    }
    val sources = remember(info) { resolveCredits(DataSourceCredits, info?.sources.orEmpty()) }
    val models = remember { resolveCredits(ModelCredits, emptyList()) }
    val bottomInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

    RouteScaffold(strings.text("About & Attribution"), onBack, modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(strings.text("Version {0}", session.env.appVersion.ifBlank { "—" }),
                        style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    Text(strings.text("Dataset {0}", info?.datasetVersion?.ifBlank { null } ?: "—"),
                        style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    Text(strings.text(AttributionIntro), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
            }
            item("sources-heading") { SectionHeading(strings.text("Data sources")) }
            items(sources, key = { "source-${it.credit.name}" }) { CreditCard(it, session.env.links::open) }
            item("models-heading") { SectionHeading(strings.text("Models and engines")) }
            items(models, key = { "model-${it.credit.name}" }) { CreditCard(it, session.env.links::open) }
            item("divider") { HorizontalDivider(color = palette.border, modifier = Modifier.padding(vertical = 6.dp)) }
            item("licences") {
                OutlinedButton(onClick = { showLicences = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Policy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.text("Open-source library licenses"))
                }
            }
            item("privacy") { LinkRow(strings.text("Privacy policy"), onClick = session::openPrivacy) }
        }
    }
    if (showLicences) LibraryLicencesDialog(onDismiss = { showLicences = false }, onOpen = session.env.links::open)
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalDictionaryPalette.current.fg,
        modifier = Modifier.padding(top = 10.dp).semantics { heading() },
    )
}

@Composable
private fun CreditCard(resolved: ResolvedCredit, onOpen: (String) -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val credit = resolved.credit
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = palette.panel, border = BorderStroke(1.dp, palette.border)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(credit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = palette.fg)
            if (credit.role.isNotBlank()) {
                Text(strings.text(credit.role), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            if (credit.licence.isNotBlank()) {
                Text(strings.text(credit.licence), style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }
            // SET-042: which build of the source the installed dataset carries.
            val version = listOf(resolved.version, resolved.date.substringBefore('T')).filter { it.isNotBlank() }.joinToString(" · ")
            if (version.isNotBlank()) {
                Text(strings.text("Version {0}", version), style = MaterialTheme.typography.labelMedium, color = palette.muted)
            }
            credit.links.forEach { link ->
                TextButton(onClick = { onOpen(link.url) }, modifier = Modifier.align(Alignment.End)) {
                    Text(link.label)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = strings.text("Open {0}", link.label))
                }
            }
        }
    }
}

/** SET-043: direct runtime library dependencies with version, role, licence and URL. */
@Composable
internal fun LibraryLicencesDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = palette.bg,
            border = BorderStroke(1.dp, palette.border),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.text("Open-source libraries"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = palette.fg)
                        Text(strings.text("Direct runtime dependencies used by this build"), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = strings.text("Close licenses"), tint = palette.fg) }
                }
                HorizontalDivider(color = palette.border)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(LibraryCredits, key = { it.name }) { library -> LibraryCard(library) { onOpen(library.url) } }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(library: LibraryCredit, onOpen: () -> Unit) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = palette.panel,
        border = BorderStroke(1.dp, palette.border)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(library.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = palette.fg,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(library.version, style = MaterialTheme.typography.labelMedium, color = palette.accent)
                }
                Text(strings.text(library.role), style = MaterialTheme.typography.bodySmall, color = palette.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(strings.text(library.licence), style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = strings.text("Open {0}", library.name), tint = palette.accent)
        }
    }
}
