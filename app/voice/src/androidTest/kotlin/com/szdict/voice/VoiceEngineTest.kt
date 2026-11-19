package com.szdict.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Decodes the model's own 16 kHz test recordings on the device (fetched by
 * tools/fetch-voice-model.sh into the androidTest assets; skipped when absent).
 */
@RunWith(AndroidJUnit4::class)
class VoiceEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun recognisesTheBundledTestRecordings() {
        val assets = instrumentation.targetContext.assets
        val testAssets = instrumentation.context.assets
        val wavs = testAssets.list("voice-test-bilingual").orEmpty()
        assumeTrue("library, model and test recordings are fetched", VoiceEngines.AVAILABLE && VoiceModel.isBundled(assets) && "0.wav" in wavs)

        val loadStart = System.nanoTime()
        val engine = VoiceEngines.open(assets)
        println("VOICE-TEST model loaded in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
        try {
            val samples = readWav(testAssets.open("voice-test-bilingual/0.wav").use { it.readBytes() })
            val stream = engine.createStream()
            val committed = ArrayList<String>()
            var previous = ""
            val start = System.nanoTime()
            // 100 ms frames, as the capture delivers them.
            var i = 0
            while (i < samples.size) {
                val end = minOf(samples.size, i + VoiceModel.SAMPLE_RATE / 10)
                stream.accept(samples.copyOfRange(i, end))
                val text = stream.text()
                assertTrue(text.startsWith(previous), "live text is append-only: '$previous' -> '$text'")
                previous = text
                if (stream.isEndpoint()) {
                    if (text.isNotBlank()) committed += text
                    stream.reset()
                    previous = ""
                }
                i = end
            }
            stream.finish()
            stream.text().takeIf { it.isNotBlank() }?.let { committed += it }
            stream.release()
            val ms = (System.nanoTime() - start) / 1_000_000
            println("VOICE-TEST 0.wav (${samples.size / VoiceModel.SAMPLE_RATE.toFloat()} s, decoded in $ms ms): $committed")
            val transcript = committed.joinToString(" ")
            assertTrue("昨天" in transcript, "Expected Mandarin in the mixed recording: $transcript")
            assertTrue("monday" in transcript.lowercase(), "Expected English in the mixed recording: $transcript")
        } finally {
            engine.release()
        }
    }

    /** 16-bit PCM mono WAV → floats. */
    private fun readWav(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            if (id == "data") {
                val count = minOf(size, bytes.size - position - 8) / 2
                return FloatArray(count) { buffer.getShort(position + 8 + it * 2) / 32768f }
            }
            position += 8 + size + (size and 1)
        }
        error("no data chunk")
    }
}
