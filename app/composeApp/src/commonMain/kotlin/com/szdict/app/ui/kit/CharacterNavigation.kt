package com.szdict.app.ui.kit

internal data class VisibleCharacterPosition(
    val offset: Int,
    val text: String
)

internal data class VisibleCharacterNavigation(
    val selected: VisibleCharacterPosition,
    val previous: VisibleCharacterPosition?,
    val next: VisibleCharacterPosition?
)

internal data class SpanSheetNavigation(
    val selectedText: String,
    val canNavigatePrevious: Boolean,
    val canNavigateNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit
)

internal fun visibleCharacterPositions(text: String): List<VisibleCharacterPosition> = buildList {
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val value = String(intArrayOf(codePoint), 0, 1)
        if (codePoint != ' '.code && codePoint != '\t'.code && codePoint != '\r'.code && codePoint != '\n'.code) {
            add(VisibleCharacterPosition(offset, value))
        }
        offset += Character.charCount(codePoint)
    }
}

internal fun visibleCharacterNavigation(
    text: String,
    selectedOffset: Int?
): VisibleCharacterNavigation? {
    val selected = selectedOffset ?: return null
    val positions = visibleCharacterPositions(text)
    val index = positions.indexOfFirst { it.offset == selected }
    if (index < 0) return null
    return VisibleCharacterNavigation(
        selected = positions[index],
        previous = positions.getOrNull(index - 1),
        next = positions.getOrNull(index + 1)
    )
}

internal fun visibleCharacterOffsetAt(text: String, offset: Int): Int? {
    if (offset !in text.indices) return null
    val normalized = if (
        offset > 0 &&
        text[offset].isLowSurrogate() &&
        text[offset - 1].isHighSurrogate()
    ) {
        offset - 1
    } else {
        offset
    }
    return visibleCharacterPositions(text)
        .firstOrNull { normalized in it.offset until it.offset + it.text.length }
        ?.offset
}
