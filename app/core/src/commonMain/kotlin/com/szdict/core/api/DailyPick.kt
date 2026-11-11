package com.szdict.core.api

/** Deterministic daily word pick shared by the Word of the Day card and the widget (SET-063). */
object DailyPick {
    /** splitmix64, exactly as specified in SET-063 (unsigned 64-bit, wrap-around). */
    fun splitmix64(x: ULong): ULong {
        var z = x + 0x9E3779B97F4A7C15uL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** The hash input: `epochDay XOR (levelMask << 32)` on 64-bit two's complement. */
    fun seed(epochDay: Long, levelMask: Int): ULong =
        epochDay.toULong() xor ((levelMask.toULong() and 0x7FuL) shl 32)

    /** Index into the candidate list (sorted by entry id, de-duplicated), or -1 when [candidateCount] is 0. */
    fun index(epochDay: Long, levelMask: Int, candidateCount: Int): Int {
        if (candidateCount <= 0) return -1
        return (splitmix64(seed(epochDay, levelMask)) % candidateCount.toULong()).toInt()
    }

    /** Full level mask (all seven levels) used by the in-app Word of the Day card (SRCH-115). */
    const val ALL_LEVELS: Int = 0x7F
}
