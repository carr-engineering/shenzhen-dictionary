package com.szdict.app.feature.search

import kotlin.math.floor
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT
import platform.Foundation.timeIntervalSince1970

internal actual fun localEpochDay(): Long {
    val now = NSDate()
    val local = now.timeIntervalSince1970 + NSTimeZone.localTimeZone.secondsFromGMTForDate(now).toDouble()
    return floor(local / 86_400.0).toLong()
}
