package com.suhli.mongosh.formatter

/**
 * Pure helpers for choosing which editor owns a formatting request.
 *
 * Document identity is the only valid match key. Matching on [textLength] alone
 * is forbidden — another open document can share the same length.
 */
object TargetEditorResolver {
    data class EditorRef(
        val id: Any,
        val documentId: Any,
        val textLength: Int,
        val hasSelection: Boolean,
        val isSelectedTextEditor: Boolean,
    )

    /**
     * Returns the target editor id for caret/selection restore, or null when
     * no editor can be reliably tied to [targetDocumentId].
     */
    fun resolve(targetDocumentId: Any, editors: List<EditorRef>): Any? {
        val forDocument = editors.filter { it.documentId === targetDocumentId || it.documentId == targetDocumentId }
        if (forDocument.isEmpty()) {
            return null
        }
        forDocument.firstOrNull { it.hasSelection && it.isSelectedTextEditor }?.let { return it.id }
        forDocument.firstOrNull { it.hasSelection }?.let { return it.id }
        forDocument.firstOrNull { it.isSelectedTextEditor }?.let { return it.id }
        return forDocument.firstOrNull()?.id
    }

    /** Rejects candidates that only share text length with the target document. */
    fun accepts(editorDocumentId: Any, targetDocumentId: Any, editorTextLength: Int, targetTextLength: Int): Boolean {
        if (editorDocumentId === targetDocumentId || editorDocumentId == targetDocumentId) {
            return true
        }
        // Same length alone is never enough.
        return false
    }
}

/**
 * Captures enough selection state to restore normal and reverse selections.
 * [selectionAnchor] is the fixed end; [caretOffset] is the moving end (lead).
 */
data class CaretSnapshot(
    val targetEditorId: Any?,
    val caretOffset: Int,
    val hasSelection: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
    val selectionAnchor: Int,
)

object CaretRestorePolicy {
    data class RestorePlan(
        val caretOffset: Int,
        val hasSelection: Boolean,
        val anchorOffset: Int,
        val leadOffset: Int,
    )

    fun plan(original: CaretSnapshot?, mappedCaret: Int?, documentLength: Int): RestorePlan? {
        if (original == null && mappedCaret == null) {
            return null
        }
        val caret = (mappedCaret ?: original?.caretOffset ?: 0).coerceIn(0, documentLength)
        if (original?.hasSelection != true) {
            return RestorePlan(caretOffset = caret, hasSelection = false, anchorOffset = caret, leadOffset = caret)
        }
        val anchor = original.selectionAnchor.coerceIn(0, documentLength)
        // Prefer keeping the original direction: anchor stays put, lead follows caret.
        val lead = if (mappedCaret != null) {
            caret
        } else {
            original.caretOffset.coerceIn(0, documentLength)
        }
        return RestorePlan(
            caretOffset = lead,
            hasSelection = anchor != lead,
            anchorOffset = anchor,
            leadOffset = lead,
        )
    }
}
