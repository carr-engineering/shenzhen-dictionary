package com.szdict.app.feature.search

import com.szdict.core.api.InputMethod

/** What tapping the Search destination again does (PROD-053). */
enum class SearchReselectAction { SCROLL_TO_TOP, FOCUS_KEYBOARD, OPEN_HANDWRITING }

/**
 * A list that is not at its top scrolls there first; once it is at the top, the tap puts the user
 * into the search field with the input method they chose: the soft keyboard, or the handwriting panel.
 */
fun searchReselectAction(listAtTop: Boolean, method: InputMethod): SearchReselectAction = when {
    !listAtTop -> SearchReselectAction.SCROLL_TO_TOP
    method == InputMethod.HANDWRITING -> SearchReselectAction.OPEN_HANDWRITING
    else -> SearchReselectAction.FOCUS_KEYBOARD
}
