package com.szdict.app.feature.conversion

import com.szdict.app.AppEnv
import io.github.opencckmp.Dict
import io.github.opencckmp.DictionaryCache
import io.github.opencckmp.DictionaryReader
import io.github.opencckmp.FileNotFound
import io.github.opencckmp.ResourceProvider
import io.github.opencckmp.SimpleConverter
import io.github.opencckmp.convertWithMap
import io.github.opencckmp.detect.ChineseScript
import io.github.opencckmp.detect.DetectionConfidence
import io.github.opencckmp.detect.ScriptDetector
import io.github.opencckmp.detect.TraditionalRegion
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Source range → target range, half-open UTF-16 offsets. Ranges are ordered and cover both texts. */
data class SegmentMapping(val sourceStart: Int, val sourceEnd: Int, val targetStart: Int, val targetEnd: Int)

/**
 * A converted text with its segment map (ARCH-196): phrase conversion can change lengths, so taps,
 * highlights and OCR boxes are mapped through [segmentMap].
 */
data class Converted(val text: String, val segmentMap: List<SegmentMapping>) {
    /** True when the conversion changed nothing (`Nothing to convert`, TXT-084). */
    fun isIdentityOf(original: String): Boolean = text == original

    /** The target range covering source offset [offset], or null when out of range. */
    fun targetRangeOf(offset: Int): IntRange? =
        find(offset, { it.sourceStart }, { it.sourceEnd })?.let { it.targetStart until it.targetEnd }

    /** The source range covering target offset [offset], or null when out of range. */
    fun sourceRangeOf(offset: Int): IntRange? =
        find(offset, { it.targetStart }, { it.targetEnd })?.let { it.sourceStart until it.sourceEnd }

    private inline fun find(offset: Int, start: (SegmentMapping) -> Int, end: (SegmentMapping) -> Int): SegmentMapping? {
        var lo = 0
        var hi = segmentMap.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = segmentMap[mid]
            when {
                offset < start(m) -> hi = mid - 1
                offset >= end(m) -> lo = mid + 1
                else -> return m
            }
        }
        return null
    }

    companion object {
        fun identity(text: String) = Converted(text, if (text.isEmpty()) emptyList() else listOf(SegmentMapping(0, text.length, 0, text.length)))
    }
}

/**
 * On-device script detection and conversion (TXT-082…TXT-084, ARCH-196). Both calls run off the
 * caller's thread, are cancellable, and load the OpenCC dictionaries they need lazily on first use.
 * Callers keep them latest-only (GLB-040). Instances are safe to share.
 */
interface ScriptConverter {
    suspend fun detect(text: String): DetectedScript

    /**
     * Converts the original [text]. Throws [IllegalArgumentException] when the pair is not offered
     * ([ConversionRules.configs] is null) and the library's error when dictionaries cannot be loaded.
     */
    suspend fun convert(text: String, source: ScriptVariant, target: ScriptVariant): Converted
}

/** The process-wide converter (dictionaries are loaded once and shared by Reading, OCR and Voice). */
object ScriptConverters {
    private var instance: ScriptConverter? = null

    fun get(env: AppEnv): ScriptConverter =
        instance ?: OpenCcScriptConverter(openCcResourceReader(env)).also { instance = it }
}

/**
 * Reads one OpenCC resource (`s2t.json`, `STPhrases.ocd2`, …) by name. Android: the `opencc/` asset
 * directory staged by the build; on a host JVM (unit tests) the same files from the classpath.
 */
internal expect fun openCcResourceReader(env: AppEnv): (String) -> ByteArray

/** [ScriptConverter] over opencc-kmp. [readResource] must throw when a resource is missing. */
class OpenCcScriptConverter(
    readResource: (String) -> ByteArray,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : ScriptConverter {
    private val provider = ResourceProvider { name ->
        try {
            readResource(name)
        } catch (e: Exception) {
            throw FileNotFound(name)
        }
    }
    private val cache = SharedDictionaries()
    private val mutex = Mutex()
    private val converters = HashMap<String, SimpleConverter>()
    private var detector: ScriptDetector? = null

    override suspend fun detect(text: String): DetectedScript = withContext(dispatcher) {
        val detector = mutex.withLock { detector ?: ScriptDetector.fromResources(provider).also { detector = it } }
        coroutineContext.ensureActive()
        val d = detector.detect(wellFormed(text))
        val confident = d.confidence == DetectionConfidence.High
        when (d.script) {
            ChineseScript.Neutral -> DetectedScript(DetectedKind.NEUTRAL, null, false)
            ChineseScript.Mixed -> DetectedScript(DetectedKind.MIXED, null, confident)
            ChineseScript.Simplified ->
                DetectedScript(DetectedKind.SIMPLIFIED, if (confident) ScriptVariant.SIMPLIFIED else null, confident)
            ChineseScript.Traditional -> {
                val variant = when (d.region) {
                    TraditionalRegion.HongKong -> ScriptVariant.TRADITIONAL_HK
                    TraditionalRegion.Taiwan -> ScriptVariant.TRADITIONAL_TW
                    else -> ScriptVariant.TRADITIONAL
                }
                DetectedScript(DetectedKind.TRADITIONAL, if (confident) variant else null, confident)
            }
        }
    }

    override suspend fun convert(text: String, source: ScriptVariant, target: ScriptVariant): Converted =
        withContext(dispatcher) {
            val configs = requireNotNull(ConversionRules.configs(source, target)) { "No conversion from $source to $target" }
            var result: Converted? = null
            for (config in configs) {
                val converter = mutex.withLock { converters[config] ?: load(config).also { converters[config] = it } }
                val previous = result
                result = if (previous == null) convertLines(converter, wellFormed(text))
                else compose(previous, convertLines(converter, previous.text))
            }
            result ?: Converted.identity(text)
        }

    private fun load(config: String) = SimpleConverter("$config.json", provider, dictionaryCache = cache, cacheIdentity = cache)

    /** Converts line by line (no phrase spans a line break), so a long text stays cancellable. */
    private suspend fun convertLines(converter: SimpleConverter, text: String): Converted {
        val out = StringBuilder(text.length + 16)
        val map = ArrayList<SegmentMapping>()
        var start = 0
        while (start < text.length) {
            coroutineContext.ensureActive()
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            val mapped = converter.convertWithMap(text.substring(start, end))
            val targetBase = out.length
            for (e in mapped.map) {
                map += SegmentMapping(start + e.sourceStart, start + e.sourceEnd, targetBase + e.targetStart, targetBase + e.targetEnd)
            }
            out.append(mapped.output)
            start = end
        }
        return Converted(out.toString(), map)
    }

    /** Decoded dictionaries shared by every configuration (STPhrases is used by four of them). */
    private class SharedDictionaries : DictionaryCache {
        private val loaded = HashMap<String, Dict>()
        override val capacity: Int get() = Int.MAX_VALUE
        override val size: Int get() = loaded.size
        override fun load(providerIdentity: Any, resourceName: String, bytes: ByteArray, reader: DictionaryReader): Dict =
            loaded.getOrPut("${reader.type}:$resourceName") { reader.read(bytes) }
        override fun clear() = loaded.clear()
        override fun close() = loaded.clear()
    }

    internal companion object {
        /** Unpaired surrogates are replaced (same length) — the library rejects malformed UTF-16. */
        fun wellFormed(text: String): String {
            var chars: CharArray? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    i += 2
                    continue
                }
                if (c.isSurrogate()) {
                    if (chars == null) chars = text.toCharArray()
                    chars[i] = '�'
                }
                i++
            }
            return chars?.concatToString() ?: text
        }

        /**
         * Composes two maps (original → middle, middle → final) into original → final. A final-stage
         * entry that crosses first-stage boundaries yields one entry covering their whole range.
         */
        fun compose(first: Converted, second: Converted): Converted {
            val a = first.segmentMap
            val b = second.segmentMap
            val sourceLength = a.lastOrNull()?.sourceEnd ?: 0
            if (b.isEmpty()) {
                return Converted(second.text, if (sourceLength == 0) emptyList() else listOf(SegmentMapping(0, sourceLength, 0, 0)))
            }
            val out = ArrayList<SegmentMapping>(b.size)
            var i = 0
            var j = 0
            var sourcePos = 0
            while (j < b.size) {
                val targetStart = b[j].targetStart
                var middleEnd = b[j].sourceEnd
                var targetEnd = b[j].targetEnd
                j++
                var sourceEnd = sourcePos
                var consumedMiddleEnd = 0
                while (true) {
                    while (i < a.size && a[i].targetStart < middleEnd) {
                        sourceEnd = a[i].sourceEnd
                        consumedMiddleEnd = a[i].targetEnd
                        i++
                    }
                    // A first-stage entry straddles this boundary: merge with the next final-stage entry.
                    if (consumedMiddleEnd > middleEnd && j < b.size) {
                        middleEnd = b[j].sourceEnd
                        targetEnd = b[j].targetEnd
                        j++
                    } else break
                }
                out += SegmentMapping(sourcePos, sourceEnd, targetStart, targetEnd)
                sourcePos = sourceEnd
            }
            // Trailing first-stage deletions belong to the last entry.
            if (sourcePos < sourceLength) out[out.lastIndex] = out.last().copy(sourceEnd = sourceLength)
            return Converted(second.text, out)
        }
    }
}
