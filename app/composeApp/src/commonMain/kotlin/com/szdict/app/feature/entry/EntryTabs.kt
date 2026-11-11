package com.szdict.app.feature.entry

/** Entry page tabs in their fixed order (ENT-030). */
internal enum class EntryTab(val label: String) {
    DICT("DICT"),
    STROKES("STROKES"),
    WORDS("WORDS"),
}

/** `DICT` and `WORDS` always; `STROKES` only when a character of the headword has stroke data (ENT-030). */
internal fun entryTabs(hasStrokes: Boolean): List<EntryTab> =
    EntryTab.entries.filter { it != EntryTab.STROKES || hasStrokes }

/** The saved route tab, falling back to `DICT` when it is unknown or no longer available (ENT-034). */
internal fun restoredTab(tabs: List<EntryTab>, saved: String?): EntryTab =
    tabs.firstOrNull { it.name == saved } ?: EntryTab.DICT

/** Pager page for the authoritative route tab (ENT-034): `DICT` when the tab is gone. */
internal fun pageForTab(tabs: List<EntryTab>, tab: EntryTab): Int =
    tabs.indexOf(tab).takeIf { it >= 0 } ?: tabs.indexOf(EntryTab.DICT).coerceAtLeast(0)

/** Stable pager key that never collides when the tab set changes under a live pager (ENT-035). */
internal fun tabKey(tabs: List<EntryTab>, page: Int): String = tabs.getOrNull(page)?.name ?: "stale-$page"
