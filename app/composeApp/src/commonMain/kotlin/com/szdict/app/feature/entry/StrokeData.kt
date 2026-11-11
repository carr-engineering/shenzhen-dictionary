package com.szdict.app.feature.entry

import androidx.compose.runtime.Composable
import engineering.carr.hanzianimator.core.StrokeOrder
import engineering.carr.hanzianimator.data.StrokeOrderProvider
import kotlin.coroutines.cancellation.CancellationException

/**
 * The app-wide stroke-graphics provider (shenzhen-hanzi-animator bundled dataset, MED-061/071): one
 * instance for the process so its bounded caches are shared by every entry (MED-081).
 */
@Composable
internal expect fun rememberStrokeProvider(): StrokeOrderProvider

/**
 * Stroke graphics of each character that has a valid record (stroke count > 0, MED-070). Reading
 * never fails the entry: a missing or unreadable record simply has no strokes (ENT-108, MED-082).
 */
internal suspend fun loadStrokeOrders(provider: StrokeOrderProvider, characters: List<String>): Map<String, StrokeOrder> {
    val out = LinkedHashMap<String, StrokeOrder>()
    for (character in characters) {
        val order = try {
            provider.strokeOrder(character)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (order != null && order.strokeCount > 0) out[character] = order
    }
    return out
}
