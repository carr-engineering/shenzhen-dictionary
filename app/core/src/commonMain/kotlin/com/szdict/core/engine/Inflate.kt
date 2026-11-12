package com.szdict.core.engine

/**
 * Raw DEFLATE (RFC 1951) decompression in plain Kotlin, with an optional preset dictionary: the
 * dictionary is the history window the first back-references may reach into (zlib
 * `inflateSetDictionary` on a raw stream). Used where the platform has no inflater that accepts a
 * preset dictionary (the browser); the JVM targets keep `java.util.zip` and are tested for equal output.
 */
internal object PureInflate {
    private class Huffman(lengths: IntArray, offset: Int, n: Int) {
        /** Number of codes of each bit length, and the symbols ordered by code. */
        val count = IntArray(16)
        val symbol = IntArray(n)

        init {
            for (i in 0 until n) count[lengths[offset + i]]++
            count[0] = 0
            val offs = IntArray(16)
            for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
            for (i in 0 until n) {
                val len = lengths[offset + i]
                if (len != 0) symbol[offs[len]++] = i
            }
        }
    }

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val FIXED_LITERALS: Huffman
    private val FIXED_DISTANCES: Huffman

    init {
        val lengths = IntArray(288)
        for (i in 0 until 144) lengths[i] = 8
        for (i in 144 until 256) lengths[i] = 9
        for (i in 256 until 280) lengths[i] = 7
        for (i in 280 until 288) lengths[i] = 8
        FIXED_LITERALS = Huffman(lengths, 0, 288)
        FIXED_DISTANCES = Huffman(IntArray(30) { 5 }, 0, 30)
    }

    private class State(val input: ByteArray, dictionary: ByteArray?) {
        var pos = 0
        var bitBuf = 0
        var bitCount = 0
        /** Output preceded by the dictionary, so distances index one array. */
        val base = dictionary?.size ?: 0
        var out = ByteArray(base + maxOf(64, input.size * 4))
        var n = base

        init {
            dictionary?.copyInto(out)
        }

        fun bits(need: Int): Int {
            var v = bitBuf
            while (bitCount < need) {
                if (pos >= input.size) throw IllegalArgumentException("truncated deflate stream")
                v = v or ((input[pos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            bitBuf = v ushr need
            bitCount -= need
            return v and ((1 shl need) - 1)
        }

        fun decode(h: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..15) {
                code = code or bits(1)
                val count = h.count[len]
                if (code - count < first) return h.symbol[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("invalid deflate code")
        }

        fun ensure(extra: Int) {
            if (n + extra > out.size) out = out.copyOf(maxOf(out.size * 2, n + extra))
        }
    }

    fun inflate(bytes: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val s = State(bytes, dictionary?.takeIf { it.isNotEmpty() })
        var last: Boolean
        do {
            last = s.bits(1) == 1
            when (s.bits(2)) {
                0 -> stored(s)
                1 -> codes(s, FIXED_LITERALS, FIXED_DISTANCES)
                2 -> dynamic(s)
                else -> throw IllegalArgumentException("invalid deflate block type")
            }
        } while (!last)
        return s.out.copyOfRange(s.base, s.n)
    }

    private fun stored(s: State) {
        s.bitBuf = 0
        s.bitCount = 0
        if (s.pos + 4 > s.input.size) throw IllegalArgumentException("truncated deflate stream")
        val len = (s.input[s.pos].toInt() and 0xFF) or ((s.input[s.pos + 1].toInt() and 0xFF) shl 8)
        s.pos += 4
        if (s.pos + len > s.input.size) throw IllegalArgumentException("truncated deflate stream")
        s.ensure(len)
        s.input.copyInto(s.out, s.n, s.pos, s.pos + len)
        s.pos += len
        s.n += len
    }

    private fun dynamic(s: State) {
        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > 286 || ndist > 30) throw IllegalArgumentException("invalid deflate header")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[CODE_ORDER[i]] = s.bits(3)
        val codeLengths = Huffman(lengths, 0, 19)
        lengths.fill(0, 0, 19)
        var index = 0
        while (index < nlen + ndist) {
            val sym = s.decode(codeLengths)
            if (sym < 16) {
                lengths[index++] = sym
            } else {
                var value = 0
                val repeat = when (sym) {
                    16 -> {
                        if (index == 0) throw IllegalArgumentException("invalid deflate lengths")
                        value = lengths[index - 1]
                        3 + s.bits(2)
                    }
                    17 -> 3 + s.bits(3)
                    else -> 11 + s.bits(7)
                }
                if (index + repeat > nlen + ndist) throw IllegalArgumentException("invalid deflate lengths")
                repeat(repeat) { lengths[index++] = value }
            }
        }
        codes(s, Huffman(lengths, 0, nlen), Huffman(lengths, nlen, ndist))
    }

    private fun codes(s: State, literals: Huffman, distances: Huffman) {
        while (true) {
            val sym = s.decode(literals)
            when {
                sym < 256 -> {
                    s.ensure(1)
                    s.out[s.n++] = sym.toByte()
                }
                sym == 256 -> return
                else -> {
                    val li = sym - 257
                    if (li >= 29) throw IllegalArgumentException("invalid deflate length")
                    val len = LENGTH_BASE[li] + s.bits(LENGTH_EXTRA[li])
                    val di = s.decode(distances)
                    if (di >= 30) throw IllegalArgumentException("invalid deflate distance")
                    val dist = DIST_BASE[di] + s.bits(DIST_EXTRA[di])
                    if (dist > s.n) throw IllegalArgumentException("deflate distance too far back")
                    s.ensure(len)
                    val out = s.out
                    var from = s.n - dist
                    var to = s.n
                    repeat(len) { out[to++] = out[from++] }
                    s.n = to
                }
            }
        }
    }
}
