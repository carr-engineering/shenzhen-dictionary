package com.szdict.core.api

/** The shared route table (GLB-110). */
sealed interface Route {
    /** `/` ([mode] null: the persisted mode) or `/{mode}`: Search, empty state. */
    data class Home(val mode: DictMode?) : Route

    /** `/{mode}/search?q=…&side=en|zh`. */
    data class Search(val mode: DictMode, val query: String, val side: SearchSide? = null) : Route

    /** `/{mode}/word/{natural-key}`. */
    data class Word(val mode: DictMode, val naturalKey: String) : Route

    /** `/{mode}/hsk` or `/{mode}/hsk/{level}` (1–7). */
    data class Hsk(val mode: DictMode, val level: Int? = null) : Route

    data object Reading : Route
    data object Ocr : Route
    data object Voice : Route
    data object Settings : Route
    data object Privacy : Route
}

/** Route parser/builder (GLB-110…112). Parsing never fails: anything unknown falls back to Search. */
object Routes {
    const val HOST = "szdict.com"

    /**
     * Parses a path (`/zh/word/…`), a path with query, or a full URL. Unknown paths under a valid
     * mode fall back to that mode's Search, anything else to `/` (GLB-112). [voiceAvailable] is
     * false on Web, where `/voice` falls back to Search.
     */
    fun parse(url: String, voiceAvailable: Boolean = true): Route {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val slash = s.indexOf('/', scheme + 3)
            val q = s.indexOf('?', scheme + 3)
            s = when {
                slash >= 0 && (q < 0 || slash < q) -> s.substring(slash)
                q >= 0 -> "/" + s.substring(q)
                else -> "/"
            }
        }
        s = s.substringBefore('#')
        val path = s.substringBefore('?')
        val query = if ('?' in s) s.substringAfter('?') else ""
        val segs = path.split('/').filter { it.isNotEmpty() }.map { decode(it, false) }
        if (segs.isEmpty()) return Route.Home(null)
        val mode = DictMode.entries.firstOrNull { it.routeSegment == segs[0] }
        if (mode == null) {
            if (segs.size != 1) return Route.Home(null)
            return when (segs[0]) {
                "reading" -> Route.Reading
                "ocr" -> Route.Ocr
                "voice" -> if (voiceAvailable) Route.Voice else Route.Home(null)
                "settings" -> Route.Settings
                "privacy" -> Route.Privacy
                else -> Route.Home(null)
            }
        }
        if (segs.size == 1) return Route.Home(mode)
        return when (segs[1]) {
            "search" -> {
                if (segs.size != 2) return Route.Home(mode)
                val params = parseQuery(query)
                val q = SettingsCodec.normaliseLineBreaks(params["q"] ?: "")
                if (q.isBlank()) return Route.Home(mode)
                val side = when (params["side"]) {
                    "en" -> SearchSide.ENGLISH
                    "zh" -> SearchSide.CHINESE
                    else -> null
                }
                Route.Search(mode, q, side)
            }
            "word" -> if (segs.size == 3 && isNaturalKey(segs[2])) Route.Word(mode, segs[2]) else Route.Home(mode)
            "hsk" -> when (segs.size) {
                2 -> Route.Hsk(mode, null)
                3 -> segs[2].toIntOrNull()?.takeIf { it in 1..7 && segs[2].length == 1 }?.let { Route.Hsk(mode, it) } ?: Route.Home(mode)
                else -> Route.Home(mode)
            }
            else -> Route.Home(mode)
        }
    }

    /** Canonical path (with query) for [route]. */
    fun build(route: Route): String = when (route) {
        is Route.Home -> if (route.mode == null) "/" else "/${route.mode.routeSegment}"
        is Route.Search -> buildString {
            append('/').append(route.mode.routeSegment).append("/search?q=").append(encode(route.query))
            if (route.side != null) append("&side=").append(if (route.side == SearchSide.ENGLISH) "en" else "zh")
        }
        is Route.Word -> "/${route.mode.routeSegment}/word/${route.naturalKey}"
        is Route.Hsk -> "/${route.mode.routeSegment}/hsk" + (route.level?.let { "/$it" } ?: "")
        Route.Reading -> "/reading"
        Route.Ocr -> "/ocr"
        Route.Voice -> "/voice"
        Route.Settings -> "/settings"
        Route.Privacy -> "/privacy"
    }

    /** Absolute `https://szdict.com/...` URL for sharing and deep links. */
    fun url(route: Route): String = "https://$HOST" + build(route)

    /** Natural keys are 16 lowercase hex characters (DATA-014). */
    fun isNaturalKey(s: String): Boolean = s.length == 16 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val k = decode(part.substringBefore('='), true)
            if (k !in out) out[k] = decode(part.substringAfter('=', ""), true)
        }
        return out
    }

    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') sb.append(c)
            else sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun decode(s: String, plusAsSpace: Boolean): String {
        if ('%' !in s && !(plusAsSpace && '+' in s)) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = hexVal(s[i + 1])
                val lo = hexVal(s[i + 2])
                if (hi >= 0 && lo >= 0) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            if (c == '+' && plusAsSpace) bytes.add(' '.code.toByte())
            else c.toString().encodeToByteArray().forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private const val HEX = "0123456789ABCDEF"
}
