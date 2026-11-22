package com.szdict.app.platform

import com.szdict.core.api.AnalysedPassage
import com.szdict.core.api.Dictionary
import com.szdict.core.api.EntryCard
import com.szdict.core.api.EntryDetail
import com.szdict.core.api.RowRef
import com.szdict.core.api.Script
import com.szdict.core.api.ScoredText
import com.szdict.core.api.SearchOptions
import com.szdict.core.api.SearchResult
import com.szdict.core.api.SegmentationMode
import com.szdict.core.api.RelatedKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach

/**
 * The CJK characters of everything the dictionary has handed to the interface on this page — what
 * is shown or about to be. The rare-character font tier (WebFont.kt) is fetched only once one of
 * its characters is among them.
 */
internal object ShownCharacters {
    private val seen = HashSet<Int>()

    /** Bumps whenever a character not seen before arrives. */
    val version: StateFlow<Int> get() = changes
    private val changes = MutableStateFlow(0)

    fun any(predicate: (Int) -> Boolean): Boolean = seen.any(predicate)

    fun note(text: String) {
        var added = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                i++
                0x10000 + ((c.code - 0xD800) shl 10) + (text[i].code - 0xDC00)
            } else c.code
            i++
            // CJK radicals and everything above: ideographs, their extensions, description characters, strokes.
            if (cp >= 0x2E80 && seen.add(cp)) added = true
        }
        if (added) changes.value++
    }
}

/**
 * The dictionary with every answer that carries text for the interface noted in [ShownCharacters]
 * (results are data classes: their string form holds all their text). Settings-like answers
 * (dataset info, labels, HSK levels and lists, audio plans) are passed through untouched.
 */
internal class CharacterNotingDictionary(private val inner: Dictionary) : Dictionary by inner {
    private fun <T> T.noted(): T = also { ShownCharacters.note(it.toString()) }

    override suspend fun search(query: String, script: Script, options: SearchOptions): SearchResult = inner.search(query, script, options).noted()
    override fun searchProgressive(query: String, script: Script, options: SearchOptions): Flow<SearchResult> =
        inner.searchProgressive(query, script, options).onEach { ShownCharacters.note(it.toString()) }
    override suspend fun cards(refs: List<RowRef>, script: Script): List<EntryCard> = inner.cards(refs, script).noted()
    override suspend fun entryCards(ids: List<Int>, script: Script): List<EntryCard> = inner.entryCards(ids, script).noted()
    override suspend fun popular(script: Script): List<EntryCard> = inner.popular(script).noted()
    override suspend fun wordOfTheDay(epochDay: Long, script: Script, levelMask: Int): EntryCard? = inner.wordOfTheDay(epochDay, script, levelMask).noted()
    override suspend fun entryDetail(id: Int, script: Script): EntryDetail = inner.entryDetail(id, script).noted()
    override suspend fun entryOverview(id: Int, script: Script): EntryDetail = inner.entryOverview(id, script).noted()
    override suspend fun entryDetailByKey(naturalKey: String, script: Script): EntryDetail = inner.entryDetailByKey(naturalKey, script).noted()
    override suspend fun related(id: Int, kind: RelatedKind, script: Script, offset: Int, limit: Int) = inner.related(id, kind, script, offset, limit).noted()
    override suspend fun insideWords(id: Int, script: Script) = inner.insideWords(id, script).noted()
    override suspend fun examples(id: Int, script: Script, offset: Int, limit: Int) = inner.examples(id, script, offset, limit).noted()
    override suspend fun decompose(characters: List<String>, script: Script, headwordEntryId: Int?) = inner.decompose(characters, script, headwordEntryId).noted()
    override suspend fun characterParts(characters: List<String>, script: Script, headwordEntryId: Int?) = inner.characterParts(characters, script, headwordEntryId).noted()
    override suspend fun appearsIn(character: String, script: Script, offset: Int, limit: Int) = inner.appearsIn(character, script, offset, limit).noted()
    override suspend fun resolveReference(traditional: String, simplified: String, readingCanonical: String?, script: Script) =
        inner.resolveReference(traditional, simplified, readingCanonical, script).noted()
    override suspend fun analyse(text: String, segmentation: SegmentationMode): AnalysedPassage = inner.analyse(text, segmentation).noted()
    override suspend fun spansAt(passage: AnalysedPassage, offset: Int, script: Script) = inner.spansAt(passage, offset, script).noted()
    override suspend fun filterHandwritingCandidates(raw: List<String>): List<String> = inner.filterHandwritingCandidates(raw).noted()
    override suspend fun rankHandwritingCandidates(raw: List<ScoredText>): List<ScoredText> = inner.rankHandwritingCandidates(raw).noted()
    override suspend fun characterAlternatives(alternatives: List<ScoredText>): List<ScoredText> = inner.characterAlternatives(alternatives).noted()
}
