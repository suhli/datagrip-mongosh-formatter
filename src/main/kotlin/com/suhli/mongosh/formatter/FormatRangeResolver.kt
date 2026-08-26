package com.suhli.mongosh.formatter

/**
 * Decides whether a Reformat Code request should format a fragment or the whole document.
 *
 * IntelliJ / DataGrip often pass a whole-file [formattingRanges] list even when the editor
 * has a selection (especially in MongoJS consoles). The editor selection is the source of
 * truth for Ctrl+Alt+L with selected text.
 *
 * Offsets are document UTF-16 indexes, start inclusive / end exclusive.
 * A null result means "format the whole document".
 */
object FormatRangeResolver {
    fun resolve(
        sourceLength: Int,
        formattingRanges: List<Pair<Int, Int>>,
        selectionStart: Int? = null,
        selectionEnd: Int? = null,
    ): Pair<Int, Int>? {
        fragment(sourceLength, selectionStart, selectionEnd)?.let { return it }

        val fragments = formattingRanges.mapNotNull { (start, end) -> fragment(sourceLength, start, end) }
        if (fragments.isEmpty()) {
            return null
        }
        return fragments.minOf { it.first } to fragments.maxOf { it.second }
    }

    private fun fragment(sourceLength: Int, start: Int?, end: Int?): Pair<Int, Int>? {
        if (start == null || end == null) {
            return null
        }
        val lo = start.coerceAtLeast(0)
        val hi = end.coerceAtMost(sourceLength)
        if (lo >= hi) {
            return null
        }
        if (lo <= 0 && hi >= sourceLength) {
            return null
        }
        return lo to hi
    }
}
