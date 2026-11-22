package com.szdict.app.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.szdict.app.AppName
import com.szdict.app.AppPlatform
import com.szdict.app.AppSession
import com.szdict.app.i18n.AppStrings
import com.szdict.app.i18n.appStrings
import com.szdict.app.ui.theme.LocalDictionaryPalette

@Composable
internal fun FeedbackNote(session: AppSession) {
    val strings = appStrings()
    val palette = LocalDictionaryPalette.current
    val template = strings.text(
        "Made in Shenzhen with ❤️ by Marcus Carr • 马一川. If you have feedback, feature requests or a bug report, {0}! I'd love to hear from you :)",
    )
    val text = buildAnnotatedString {
        append(template.substringBefore("{0}"))
        withLink(
            LinkAnnotation.Clickable(
                tag = "feedback-email",
                styles = TextLinkStyles(SpanStyle(color = palette.accent, textDecoration = TextDecoration.Underline)),
                linkInteractionListener = {
                    session.env.links.open(feedbackEmailUrl(strings, session.env.appPlatform, session.env.appVersion))
                },
            ),
        ) { append(strings.text("send me an email")) }
        append(template.substringAfter("{0}", ""))
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
}

internal fun feedbackEmailUrl(strings: AppStrings, platform: AppPlatform, version: String): String {
    val app = AppName.full(strings.lang)
    val subject = strings.text("{0} feedback", app)
    val body = strings.text(
        "Hi Marcus!\nI'm using {0} ({1}, version {2}), and I'd like to share something with you.",
        app,
        strings.text(platform.appLabel),
        version.ifBlank { "—" },
    )
    return "mailto:marcus@carr.eng.br?subject=${subject.encodeMailQuery()}&body=${body.replace("\n", "\r\n").encodeMailQuery()}"
}

// Encode UTF-8 bytes as a mailto query value, including literal plus signs and reserved delimiters.
private fun String.encodeMailQuery(): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") {
            append(char)
        } else {
            append('%')
            append(hex[code shr 4])
            append(hex[code and 0xF])
        }
    }
}
