package com.suhli.mongosh

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.suhli.mongosh.formatter.DocumentUpdatePolicy
import com.suhli.mongosh.formatter.FormatRangeResolver
import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import com.suhli.mongosh.formatter.FormatterBackend
import com.suhli.mongosh.settings.MongoJsFormatterSettings
import com.suhli.mongosh.sidecar.QuickJsSidecarBackend
import com.suhli.mongosh.sidecar.SidecarExtractor
import com.suhli.mongosh.sidecar.SidecarProcessClient
import com.suhli.mongosh.sidecar.SidecarResolver
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class MongoJsFormattingService @JvmOverloads constructor(
    private val backend: FormatterBackend = createDefaultBackend(),
) : AsyncDocumentFormattingService() {

    override fun getName(): String = "MongoJS Formatter"

    override fun getNotificationGroupId(): String = NOTIFICATION_GROUP_ID

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean = MongoJsFileSupport.isMongoJs(file)

    override fun getTimeout(): Duration = Duration.ofSeconds(45)

    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask {
        return MongoJsFormattingTask(formattingRequest, backend)
    }

    companion object {
        const val NOTIFICATION_GROUP_ID = "MongoJS Format"
        const val SIDECAR_VERSION = "2.0.1-exact-selection-prettier-3.9.6-qjs-0.16.2"
        private val LOG = Logger.getInstance(MongoJsFormattingService::class.java)
        private val REQUEST_SEQ = AtomicInteger()

        fun createDefaultBackend(): FormatterBackend {
            val extractor = SidecarExtractor(
                cacheRoot = Path.of(PathManager.getSystemPath(), "mongojs-formatter"),
                sidecarVersion = SIDECAR_VERSION,
                resourceOpener = { path -> MongoJsFormattingService::class.java.getResourceAsStream(path) },
            )
            return QuickJsSidecarBackend(
                resolver = SidecarResolver(extractor),
                client = SidecarProcessClient(timeout = Duration.ofSeconds(40)),
            )
        }
    }

    private class MongoJsFormattingTask(
        private val request: AsyncFormattingRequest,
        private val backend: FormatterBackend,
    ) : FormattingTask {
        private val cancelled = AtomicBoolean(false)
        private val requestId = REQUEST_SEQ.incrementAndGet()
        private val caretAtCreate = snapshotCaret("createTask")

        init {
            logPlatform("createTask")
        }

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
                val caretAtRun = snapshotCaret("run")
                logPlatform("run")
                val caret = caretAtCreate?.takeIf { it.hasSelection } ?: caretAtRun
                val selection = caret?.takeIf { it.hasSelection }
                val fragment = FormatRangeResolver.resolve(
                    sourceLength = source.length,
                    formattingRanges = platformRanges(request),
                    selectionStart = selection?.selectionStart,
                    selectionEnd = selection?.selectionEnd,
                )
                val rangeSource = when {
                    caretAtCreate?.hasSelection == true -> "caretAtCreate"
                    caretAtRun?.hasSelection == true -> "caretAtRun"
                    fragment != null -> "platformRanges"
                    else -> "wholeDocument"
                }
                LOG.info(
                    "MongoJS format #$requestId resolved range=${fragment ?: "whole-document"} " +
                        "rangeSource=$rangeSource caretCreate=${describeCaret(caretAtCreate)} " +
                        "caretRun=${describeCaret(caretAtRun)}",
                )
                val formatRequest = FormatRequest(
                    source = source,
                    rangeStart = fragment?.first,
                    rangeEnd = fragment?.second,
                    cursorOffset = caret?.offset,
                    options = MongoJsFormatterSettings.getInstance().toFormatterOptions(),
                )
                LOG.info(
                    "MongoJS format #$requestId sidecar request rangeStart=${formatRequest.rangeStart} " +
                        "rangeEnd=${formatRequest.rangeEnd} cursorOffset=${formatRequest.cursorOffset} " +
                        "sourceLength=${source.length}",
                )
                val result = backend.format(formatRequest, cancelled)
                if (result is FormatResult.Failure) {
                    LOG.info("MongoJS format #$requestId sidecar failure: ${result.message}")
                    request.onError("MongoJS Formatter", result.message, errorOffset(result))
                    return
                }
                if (cancelled.get()) {
                    return
                }
                val nextText = DocumentUpdatePolicy.nextText(source, result)
                val success = result as FormatResult.Success
                LOG.info(
                    "MongoJS format #$requestId sidecar success formattedLength=${success.formatted.length} " +
                        "changed=${nextText != null} cursorOffset=${success.cursorOffset}",
                )
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

        private fun logPlatform(phase: String) {
            val file = request.context.containingFile
            val languages = file.viewProvider.languages.joinToString { it.id }
            val ranges = request.formattingRanges.joinToString { "${it.startOffset}-${it.endOffset}" }
            val contextRange = request.context.formattingRange
            LOG.info(
                "MongoJS format #$requestId [$phase] edt=${ApplicationManager.getApplication().isDispatchThread} " +
                    "file=${file.name} vf=${request.context.virtualFile?.path} lang=${file.language.id} " +
                    "langs=[$languages] sourceLength=${request.documentText.length} " +
                    "formattingRanges=[$ranges] contextRange=${contextRange.startOffset}-${contextRange.endOffset}",
            )
        }

        private fun snapshotCaret(phase: String): CaretSnapshot? {
            return ReadAction.compute<CaretSnapshot?, RuntimeException> {
                val file = request.context.containingFile
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                    ?: FileDocumentManager.getInstance().getDocument(request.context.virtualFile ?: return@compute null)
                    ?: return@compute null
                val editors = collectEditors(file, document)
                val selected = FileEditorManager.getInstance(file.project).selectedTextEditor
                LOG.info(
                    "MongoJS format #$requestId [$phase] psiDoc=#${System.identityHashCode(document)} " +
                        "psiLen=${document.textLength} editorCount=${editors.size} " +
                        "selected=${describeEditor(selected, document)}",
                )
                for (editor in editors) {
                    LOG.info(
                        "MongoJS format #$requestId [$phase] ${describeEditor(editor, document)} " +
                            "snippet='${snippet(editor.document.text, editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd)}'",
                    )
                }
                pickCaret(editors, selected, document)
            }
        }

        private fun collectEditors(file: PsiFile, document: Document): List<Editor> {
            val factory = EditorFactory.getInstance()
            val found = LinkedHashSet<Editor>()
            found.addAll(factory.getEditors(document, file.project))
            found.addAll(factory.getEditors(document))
            FileEditorManager.getInstance(file.project).selectedTextEditor?.let { found.add(it) }
            if (found.none { it.selectionModel.hasSelection() }) {
                factory.allEditors.filterTo(found) { editor ->
                    editor.document.textLength == document.textLength && editor.selectionModel.hasSelection()
                }
            }
            return found.toList()
        }

        private fun pickCaret(editors: List<Editor>, selected: Editor?, document: Document): CaretSnapshot? {
            val preferred = editors.firstOrNull { it.selectionModel.hasSelection() && it.document === document }
                ?: selected?.takeIf { it.selectionModel.hasSelection() }
                ?: editors.firstOrNull { it.selectionModel.hasSelection() }
                ?: editors.firstOrNull { it.document === document }
                ?: selected
                ?: editors.firstOrNull()
            return preferred?.let { snapshotFrom(it) }
        }

        private fun snapshotFrom(editor: Editor): CaretSnapshot {
            val caret = editor.caretModel.currentCaret
            return CaretSnapshot(
                offset = caret.offset,
                hasSelection = caret.hasSelection(),
                selectionStart = caret.selectionStart,
                selectionEnd = caret.selectionEnd,
            )
        }

        private fun describeCaret(caret: CaretSnapshot?): String {
            if (caret == null) {
                return "none"
            }
            return "offset=${caret.offset} hasSelection=${caret.hasSelection} " +
                "sel=${caret.selectionStart}-${caret.selectionEnd}"
        }

        private fun describeEditor(editor: Editor?, document: Document): String {
            if (editor == null) {
                return "null"
            }
            val selection = editor.selectionModel
            return "editor=${editor.javaClass.simpleName} doc=#${System.identityHashCode(editor.document)} " +
                "docMatch=${editor.document === document} len=${editor.document.textLength} " +
                "offset=${editor.caretModel.offset} hasSelection=${selection.hasSelection()} " +
                "sel=${selection.selectionStart}-${selection.selectionEnd}"
        }

        private fun snippet(text: String, start: Int, end: Int): String {
            if (start >= end) {
                return ""
            }
            val lo = start.coerceIn(0, text.length)
            val hi = end.coerceIn(0, text.length)
            val raw = text.substring(lo, hi).replace("\r", "\\r").replace("\n", "\\n")
            return if (raw.length <= 80) raw else raw.take(80) + "..."
        }

        private fun restoreCaret(context: FormattingContext, mappedOffset: Int?, original: CaretSnapshot?) {
            if (mappedOffset == null && original == null) {
                return
            }
            ApplicationManager.getApplication().invokeLater {
                val file = context.containingFile
                val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return@invokeLater
                val editors = EditorFactory.getInstance().getEditors(document, file.project)
                    .ifEmpty { EditorFactory.getInstance().getEditors(document) }
                val length = document.textLength
                for (editor in editors) {
                    val caretOffset = (mappedOffset ?: original?.offset ?: 0).coerceIn(0, length)
                    if (original?.hasSelection == true) {
                        val start = original.selectionStart.coerceIn(0, length)
                        editor.selectionModel.setSelection(min(start, caretOffset), max(start, caretOffset))
                    }
                    editor.caretModel.moveToOffset(caretOffset)
                }
            }
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

    private data class CaretSnapshot(
        val offset: Int,
        val hasSelection: Boolean,
        val selectionStart: Int,
        val selectionEnd: Int,
    )
}
