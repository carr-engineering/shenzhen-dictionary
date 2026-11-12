@file:OptIn(ExperimentalForeignApi::class)

package com.szdict.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv

/**
 * Decodes real recordings through the engine on the simulator. The model and the recordings are
 * read from the host (the simulator shares its file system); see the test task in build.gradle.kts.
 */
class SpeechEngineTest {
    private val modelDir = getenv("SPEECH_MODEL_DIR")?.toKString()
    private val wavDir = getenv("SPEECH_WAV_DIR")?.toKString()

    @Test
    fun streamsRecordingsLikeTheMicrophone() {
        if (!Speech.AVAILABLE || modelDir == null || wavDir == null || !SpeechModel.isPresent(modelDir)) {
            println("SpeechEngineTest skipped: engine linked=${Speech.AVAILABLE}, model=$modelDir, wavs=$wavDir")
            return
        }
        println("sherpa-onnx ${Speech.VERSION}")
        val loadStart = TimeSource.Monotonic.markNow()
        val engine = Speech.open(modelDir)
        println("model loaded in ${loadStart.elapsedNow().inWholeMilliseconds} ms")
        try {
            var decoded = 0
            for (name in listOf("1.wav", "2.wav", "6-zh-en.wav")) {
                val samples = readWav("$wavDir/$name") ?: continue
                val audioSeconds = samples.size / SpeechModel.SAMPLE_RATE.toDouble()
                val stream = engine.createStream()
                val start = TimeSource.Monotonic.markNow()
                var previous = ""
                var appendOnly = true
                // 50 ms frames, as the capture delivers them.
                val frame = SpeechModel.SAMPLE_RATE / 20
                var at = 0
                while (at < samples.size) {
                    val end = minOf(at + frame, samples.size)
                    stream.accept(samples.copyOfRange(at, end))
                    at = end
                    val text = stream.text()
                    if (!text.startsWith(previous)) appendOnly = false
                    previous = text
                }
                stream.finish()
                val text = stream.text()
                val seconds = start.elapsedNow().inWholeMicroseconds / 1e6
                stream.release()
                println("$name: ${fmt(audioSeconds)} s audio, decoded in ${fmt(seconds)} s, RTF ${fmt(seconds / audioSeconds)}: $text")
                assertTrue(text.isNotBlank(), "$name decoded to nothing")
                assertTrue(appendOnly, "$name: greedy search must be append-only")
                decoded++
            }
            assertTrue(decoded > 0, "no recordings in $wavDir")

            // Endpointing: speech followed by silence commits (rule 2), and a reset starts afresh.
            val samples = readWav("$wavDir/1.wav")
            if (samples != null) {
                val stream = engine.createStream()
                val audio = samples + FloatArray(SpeechModel.SAMPLE_RATE * 5)
                val frame = SpeechModel.SAMPLE_RATE / 10
                var at = 0
                while (at < audio.size && !stream.isEndpoint()) {
                    stream.accept(audio.copyOfRange(at, minOf(at + frame, audio.size)))
                    at += frame
                }
                val seconds = at / SpeechModel.SAMPLE_RATE.toDouble()
                println("endpoint at ${fmt(seconds)} s of a ${fmt(samples.size / SpeechModel.SAMPLE_RATE.toDouble())} s recording: ${stream.text()}")
                assertTrue(stream.isEndpoint(), "no endpoint after trailing silence")
                assertTrue(seconds > 2.0, "endpoint before the speech ended")
                assertTrue(stream.text().isNotBlank())
                stream.reset()
                assertEquals("", stream.text())
                stream.release()
            }
        } finally {
            engine.release()
        }
    }

    @Test
    fun aMissingModelIsAnError() {
        assertTrue(runCatching { Speech.open("/nonexistent/voice-model") }.isFailure)
    }

    private fun fmt(value: Double): String = ((value * 1000).toLong() / 1000.0).toString()

    /** 16-bit PCM mono 16 kHz wav → floats in −1…1; null when the file is absent. */
    private fun readWav(path: String): FloatArray? {
        val file = fopen(path, "rb") ?: return null
        val bytes = try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)
            ByteArray(size).also { b -> b.usePinned { fread(it.addressOf(0), 1u, size.toULong(), file) } }
        } finally {
            fclose(file)
        }
        fun u16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        var at = 12
        var channels = 1
        while (at + 8 <= bytes.size) {
            val id = bytes.decodeToString(at, at + 4)
            val size = u32(at + 4)
            if (id == "fmt ") {
                check(u16(at + 8) == 1 && u16(at + 22) == 16) { "$path: 16-bit PCM expected" }
                channels = u16(at + 10)
                check(u32(at + 12) == SpeechModel.SAMPLE_RATE) { "$path: 16 kHz expected" }
            } else if (id == "data") {
                val count = minOf(size, bytes.size - at - 8) / 2 / channels
                return FloatArray(count) { i -> u16(at + 8 + i * 2 * channels).toShort() / 32768f }
            }
            at += 8 + size + (size and 1)
        }
        error("$path: no data chunk")
    }
}
