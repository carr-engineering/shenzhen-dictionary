package com.szdict.app.platform

/**
 * Rewraps an Ogg Opus clip as Core Audio Format without touching the audio: the Opus packets are
 * copied as they are, only the container changes. AVAudioPlayer plays Opus in CAF; it does not
 * take the Ogg container. The layout is the one `afconvert -f caff -d opus` writes: `desc`,
 * `chan`, `kuki`, `pakt` (packet sizes, priming = the Ogg pre-skip, remainder from the last
 * granule position) and `data`.
 *
 * Returns null when [ogg] is not a mono or stereo Ogg Opus stream with one constant frame size.
 */
internal fun oggOpusToCaf(ogg: ByteArray): ByteArray? {
    // ---- Ogg pages -> packets (a segment shorter than 255 bytes ends a packet) ----
    val packets = ArrayList<ByteArray>()
    var pending = ByteArray(0)
    var lastGranule = 0L
    var at = 0
    while (at + 27 <= ogg.size) {
        if (ogg[at] != 'O'.code.toByte() || ogg[at + 1] != 'g'.code.toByte() || ogg[at + 2] != 'g'.code.toByte() || ogg[at + 3] != 'S'.code.toByte()) return null
        var granule = 0L
        for (i in 7 downTo 0) granule = (granule shl 8) or (ogg[at + 6 + i].toLong() and 0xFF)
        val segments = ogg[at + 26].toInt() and 0xFF
        var body = at + 27 + segments
        if (body > ogg.size) return null
        for (s in 0 until segments) {
            val length = ogg[at + 27 + s].toInt() and 0xFF
            if (body + length > ogg.size) return null
            pending += ogg.copyOfRange(body, body + length)
            body += length
            if (length < 255) {
                packets.add(pending)
                pending = ByteArray(0)
            }
        }
        if (granule >= 0) lastGranule = granule
        at = body
    }
    // OpusHead, OpusTags, then audio.
    if (packets.size < 3) return null
    val head = packets[0]
    if (head.size < 19 || head.decodeToString(0, 8) != "OpusHead") return null
    val channels = head[9].toInt() and 0xFF
    if (channels != 1 && channels != 2) return null
    val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
    val audio = packets.subList(2, packets.size)
    if (audio.any { it.isEmpty() }) return null
    val framesPerPacket = opusPacketFrames(audio[0])
    if (framesPerPacket <= 0 || audio.any { opusPacketFrames(it) != framesPerPacket }) return null
    val totalFrames = audio.size.toLong() * framesPerPacket
    val validFrames = (lastGranule - preSkip).coerceIn(0, maxOf(0, totalFrames - preSkip))
    val remainder = (totalFrames - preSkip - validFrames).coerceAtLeast(0)

    // ---- CAF (big-endian) ----
    val out = ByteWriter(ogg.size + 256)
    out.ascii("caff"); out.u16(1); out.u16(0)
    out.ascii("desc"); out.u64(32)
    out.u64(48000.0.toRawBits()); out.ascii("opus"); out.u32(0); out.u32(0); out.u32(framesPerPacket); out.u32(channels); out.u32(0)
    out.ascii("chan"); out.u64(12)
    out.u32(if (channels == 1) 0x00640001 else 0x00650002); out.u32(0); out.u32(0)
    out.ascii("kuki"); out.u64(28)
    out.u32(0x800); out.u32(48000); out.u32(framesPerPacket); out.u32(-1000); out.u32(channels); out.u32(0); out.u32(0)
    val sizes = ByteWriter(audio.size * 2)
    for (packet in audio) sizes.varint(packet.size)
    out.ascii("pakt"); out.u64(24L + sizes.size)
    out.u64(audio.size.toLong()); out.u64(validFrames); out.u32(preSkip); out.u32(remainder.toInt())
    out.bytes(sizes.toByteArray())
    out.ascii("data"); out.u64(4L + audio.sumOf { it.size })
    out.u32(0)
    for (packet in audio) out.bytes(packet)
    return out.toByteArray()
}

/** Samples (at 48 kHz) one Opus packet decodes to, from its TOC byte (RFC 6716 §3.1); 0 when malformed. */
private fun opusPacketFrames(packet: ByteArray): Int {
    val toc = packet[0].toInt() and 0xFF
    val config = toc shr 3
    val frameSize = when {
        config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
        config < 16 -> intArrayOf(480, 960)[config and 1]
        else -> intArrayOf(120, 240, 480, 960)[config and 3]
    }
    val count = when (toc and 3) {
        0 -> 1
        1, 2 -> 2
        else -> if (packet.size < 2) 0 else packet[1].toInt() and 0x3F
    }
    return frameSize * count
}

private class ByteWriter(capacity: Int) {
    private var buffer = ByteArray(capacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    fun ascii(text: String) = bytes(text.encodeToByteArray())
    fun u16(value: Int) { ensure(2); buffer[size++] = (value shr 8).toByte(); buffer[size++] = value.toByte() }
    fun u32(value: Int) { u16(value ushr 16); u16(value and 0xFFFF) }
    fun u64(value: Long) { u32((value ushr 32).toInt()); u32(value.toInt()) }

    /** CAF variable-length integer: 7 bits per byte, most significant first, high bit = more follow. */
    fun varint(value: Int) {
        var shift = 28
        while (shift > 0 && (value ushr shift) == 0) shift -= 7
        while (shift > 0) {
            ensure(1); buffer[size++] = (((value ushr shift) and 0x7F) or 0x80).toByte()
            shift -= 7
        }
        ensure(1); buffer[size++] = (value and 0x7F).toByte()
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
