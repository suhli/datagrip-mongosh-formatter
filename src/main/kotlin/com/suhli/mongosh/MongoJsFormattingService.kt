package com.suhli.mongosh

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.suhli.mongosh.formatter.CaretRestorePolicy
import com.suhli.mongosh.formatter.CaretSnapshot
import com.suhli.mongosh.formatter.DocumentUpdatePolicy
import com.suhli.mongosh.formatter.FormatRangeResolver
import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import com.suhli.mongosh.formatter.FormatSizePolicy
import com.suhli.mongosh.formatter.FormatterBackend
import com.suhli.mongosh.formatter.TargetEditorResolver
import com.suhli.mongosh.settings.MongoJsFormatterSettings
import com.suhli.mongosh.sidecar.QuickJsSidecarBackend
import com.suhli.mongosh.sidecar.SidecarExtractor
import com.suhli.mongosh.sidecar.SidecarProcessClient
import com.suhli.mongosh.sidecar.SidecarResolver
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MongoJsFormattingService @JvmOverloads constructor(
    private val backend: FormatterBackend = createDefaultBackend(),
) : AsyncDocumentFormattingService() {

    override fun getName(): String = "MongoJS Formatter"

    override fun getNotificationGroupId(): String = NOTIFICATION_GROUP_ID

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean = MongoJsFileSupport.isMongoJs(file)

    override fun getTimeout() = FormatSizePolicy.FORMATTER_TIMEOUT

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask {
        return MongoJsFormattingTask(formattingRequest, backend)
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "MongoJS Format"
        private val LOG = Logger.getInstance(MongoJsFormattingService::class.java)
        private val REQUEST_SEQ = AtomicInteger()

        fun createDefaultBackend(): FormatterBackend {
            val extractor = SidecarExtractor(
                cacheRoot = Path.of(PathManager.getSystemPath(), "mongojs-formatter"),
                resourceOpener = { path -> MongoJsFormattingService::class.java.getResourceAsStream(path) },
            )
            return QuickJsSidecarBackend(
                resolver = SidecarResolver(extractor),
                client = SidecarProcessClient(),
            )
        }
    }

    private class MongoJsFormattingTask(
        private val request: AsyncFormattingRequest,
        private val backend: FormatterBackend,
    ) : FormattingTask {
        private val cancelled = AtomicBoolean(false)
        private val requestId = REQUEST_SEQ.incrementAndGet()
        private val targetEditorRef = AtomicReference<Editor?>(null)
        private val caretAtCreate = snapshotCaret()

        override fun isRunUnderProgress(): Boolean = true

        override fun cancel(): Boolean {
            cancelled.set(true)
            return true
        }

        override fun run() {
            try {
                if (cancelled.get()) {
                    return
                }
                val source = request.documentText
                if (FormatSizePolicy.isTooLarge(source.length)) {
                    request.onError("MongoJS Formatter", FormatSizePolicy.rejectMessage(source.length), -1)
                    return
                }
                val caretAtRun = snapshotCaret()
                val caret = caretAtCreate?.takeIf { it.hasSelection } ?: caretAtRun
                val selection = caret?.takeIf { it.hasSelection }
                val fragment = FormatRangeResolver.resolve(
                    sourceLength = source.length,
                    formattingRanges = platformRanges(request),
                    selectionStart = selection?.selectionStart,
                    selectionEnd = selection?.selectionEnd,
                )
                LOG.debug(
                    "MongoJS format #$requestId range=${fragment ?: "whole-document"} " +
                        "sourceLength=${source.length} hasSelection=${selection != null}",
                )
                val formatRequest = FormatRequest(
                    source = source,
                    rangeStart = fragment?.first,
                    rangeEnd = fragment?.second,
                    cursorOffset = caret?.caretOffset,
                    options = MongoJsFormatterSettings.getInstance().toFormatterOptions(),
                )
                val result = backend.format(formatRequest, cancelled)
                if (result is FormatResult.Failure) {
                    LOG.debug("MongoJS format #$requestId failure: ${result.message}")
                    request.onError("MongoJS Formatter", result.message, errorOffset(result))
                    return
                }
                if (cancelled.get()) {
                    return
                }
                val nextText = DocumentUpdatePolicy.nextText(source, result)
                val success = result as FormatResult.Success
                request.onTextReady(nextText)
                if (nextText != null) {
                    restoreCaret(request.context, success.cursorOffset, caret)
                }
            } catch (error: ProcessCanceledException) {
                throw error
            } catch (error: Exception) {
                LOG.warn("MongoJS formatting failed", error)
                request.onError(
                    "MongoJS Formatter",
                    error.message?.takeIf { it.isNotBlank() } ?: "Formatting failed",
                    -1,
                )
            }
        }

        private fun platformRanges(request: AsyncFormattingRequest): List<Pair<Int, Int>> {
            return buildList {
                for (range in request.formattingRanges) {
                    add(range.startOffset to range.endOffset)
                }
                val contextRange = request.context.formattingRange
                add(contextRange.startOffset to contextRange.endOffset)
            }.distinct()
        }

        private fun snapshotCaret(): CaretSnapshot? {
            return ApplicationManager.getApplication().runReadAction<CaretSnapshot?> {
                val file = request.context.containingFile
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                    ?: FileDocumentManager.getInstance().getDocument(request.context.virtualFile ?: return@runReadAction null)
                    ?: return@runReadAction null
                val selected = FileEditorManager.getInstance(file.project).selectedTextEditor
                    ?.takeIf { it.document === document }
                val editors = collectEditorsForDocument(file, document)
                val refs = editors.map { editor ->
                    TargetEditorResolver.EditorRef(
                        id = editor,
                        documentId = editor.document,
                        textLength = editor.document.textLength,
                        hasSelection = editor.selectionModel.hasSelection(),
                        isSelectedTextEditor = editor === selected,
                    )
                }
                val targetId = TargetEditorResolver.resolve(document, refs)
                val target = editors.firstOrNull { it === targetId || it == targetId }
                if (target != null) {
                    targetEditorRef.compareAndSet(null, target)
                }
                target?.let { snapshotFrom(it) }
            }
        }

        private fun collectEditorsForDocument(file: PsiFile, document: Document): List<Editor> {
            val factory = EditorFactory.getInstance()
            val found = LinkedHashSet<Editor>()
            factory.getEditors(document, file.project).filterTo(found) { it.document === document }
            factory.getEditors(document).filterTo(found) { it.document === document }
            FileEditorManager.getInstance(file.project).selectedTextEditor
                ?.takeIf { it.document === document }
                ?.let { found.add(it) }
            return found.toList()
        }

        private fun snapshotFrom(editor: Editor): CaretSnapshot {
            val selection = editor.selectionModel
            val caret = editor.caretModel.currentCaret
            val hasSelection = selection.hasSelection()
            val start = selection.selectionStart
            val end = selection.selectionEnd
            val lead = if (hasSelection) selection.leadSelectionOffset else caret.offset
            val anchor = when {
                !hasSelection -> caret.offset
                lead == start -> end
                else -> start
            }
            return CaretSnapshot(
                targetEditorId = editor,
                caretOffset = caret.offset,
                hasSelection = hasSelection,
                selectionStart = start,
                selectionEnd = end,
                selectionAnchor = anchor,
            )
        }

        private fun restoreCaret(context: FormattingContext, mappedOffset: Int?, original: CaretSnapshot?) {
            if (original == null && mappedOffset == null) {
                return
            }
            ApplicationManager.getApplication().invokeLater {
                val file = context.containingFile
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return@invokeLater
                val length = document.textLength
                val target = resolveTargetEditor(original) ?: return@invokeLater
                if (target.isDisposed || target.document !== document) {
                    return@invokeLater
                }
                val restored = CaretRestorePolicy.plan(original, mappedOffset, length) ?: return@invokeLater
                if (restored.hasSelection) {
                    target.selectionModel.setSelection(restored.anchorOffset, restored.leadOffset)
                } else {
                    target.selectionModel.removeSelection()
                    target.caretModel.moveToOffset(restored.caretOffset)
                }
            }
        }

        private fun resolveTargetEditor(original: CaretSnapshot?): Editor? {
            val held = targetEditorRef.get()
            if (held != null && !held.isDisposed) {
                return held
            }
            val fromSnapshot = original?.targetEditorId as? Editor
            if (fromSnapshot != null && !fromSnapshot.isDisposed) {
                return fromSnapshot
            }
            return null
        }

        private fun errorOffset(result: FormatResult.Failure): Int {
            val line = result.line ?: return -1
            val column = result.column ?: 0
            val documentText = request.documentText
            var currentLine = 1
            var index = 0
            while (index < documentText.length && currentLine < line) {
                if (documentText[index] == '\n') {
                    currentLine += 1
                }
                index += 1
            }
            return (index + (column - 1).coerceAtLeast(0)).coerceIn(0, documentText.length)
        }
    }
}
