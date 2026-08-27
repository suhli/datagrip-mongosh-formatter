package com.suhli.mongosh.formatter

object DocumentUpdatePolicy {
    /**
     * Returns the next document text, or null when nothing should be applied.
     *
     * When a selection/fragment range is present, results that are equivalent to a
     * whole-document rewrite are refused. Prettier range formatting may expand to an
     * enclosing AST node (allowed); silently rewriting the rest of a Mongo console is not.
     */
    fun nextText(
        original: String,
        result: FormatResult,
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
    ): String? {
        val success = result as? FormatResult.Success ?: return null
        val formatted = success.formatted
        if (
            rangeStart != null &&
            rangeEnd != null &&
            rangeStart < rangeEnd &&
            looksLikeWholeDocumentRewrite(original, formatted, rangeStart, rangeEnd)
        ) {
            return null
        }
        return formatted.takeIf { it != original }
    }

    /**
     * Heuristic: selection is a fragment, yet almost none of the non-selected text
     * survived as a common prefix/suffix — typical of an accidental full-buffer format.
     */
    fun looksLikeWholeDocumentRewrite(
        original: String,
        formatted: String,
        rangeStart: Int,
        rangeEnd: Int,
    ): Boolean {
        val start = rangeStart.coerceIn(0, original.length)
        val end = rangeEnd.coerceIn(0, original.length)
        if (start >= end) {
            return false
        }
        val selectionLen = end - start
        if (selectionLen >= original.length * 0.9) {
            return false
        }
        // Fast path: outside the selection is still byte-identical.
        if (formatted.startsWith(original.substring(0, start)) &&
            formatted.endsWith(original.substring(end))
        ) {
            return false
        }
        var commonPrefix = 0
        val maxPrefix = minOf(original.length, formatted.length)
        while (commonPrefix < maxPrefix && original[commonPrefix] == formatted[commonPrefix]) {
            commonPrefix += 1
        }
        var commonSuffix = 0
        val maxSuffix = minOf(original.length - commonPrefix, formatted.length - commonPrefix)
        while (
            commonSuffix < maxSuffix &&
            original[original.length - 1 - commonSuffix] == formatted[formatted.length - 1 - commonSuffix]
        ) {
            commonSuffix += 1
        }
        val preserved = commonPrefix + commonSuffix
        val outsideSelection = original.length - selectionLen
        return preserved < outsideSelection / 2
    }
}
