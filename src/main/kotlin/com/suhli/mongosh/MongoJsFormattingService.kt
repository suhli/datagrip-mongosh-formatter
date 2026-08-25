package com.suhli.mongosh

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.suhli.mongosh.formatter.DocumentUpdatePolicy
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
        const val SIDECAR_VERSION = "2.0.0-prettier-3.9.6-qjs-0.16.2"
        private val LOG = Logger.getInstance(MongoJsFormattingService::class.java)

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
                val (rangeStart, rangeEnd) = selectedRange(source, request.formattingRanges)
                val caret = currentCaret(request.context)
                val formatRequest = FormatRequest(
                    source = source,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    cursorOffset = caret?.offset,
                    options = MongoJsFormatterSettings.getInstance().toFormatterOptions(),
                )
                val result = backend.format(formatRequest, cancelled)
                if (result is FormatResult.Failure) {
                    request.onError("MongoJS Formatter", result.message, errorOffset(result))
                    return
                }
                if (cancelled.get()) {
                    return
                }
                val nextText = DocumentUpdatePolicy.nextText(source, result)
                request.onTextReady(nextText)
                if (nextText != null && result is FormatResult.Success) {
                    restoreCaret(request.context, result.cursorOffset, caret)
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

        private fun selectedRange(source: String, ranges: List<TextRange>): Pair<Int?, Int?> {
            if (ranges.isEmpty()) {
                return null to null
            }
            val start = ranges.minOf { it.startOffset }
            val end = ranges.maxOf { it.endOffset }
            if (start <= 0 && end >= source.length) {
                return null to null
            }
            return start to end
        }

        private fun currentCaret(context: FormattingContext): CaretSnapshot? {
            val file = context.containingFile
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
                ?: FileDocumentManager.getInstance().getDocument(context.virtualFile ?: return null)
                ?: return null
            val editor = EditorFactory.getInstance().getEditors(document, file.project).firstOrNull()
                ?: EditorFactory.getInstance().getEditors(document).firstOrNull()
                ?: return null
            val caret = editor.caretModel.currentCaret
            return CaretSnapshot(
                offset = caret.offset,
                hasSelection = caret.hasSelection(),
                selectionStart = caret.selectionStart,
                selectionEnd = caret.selectionEnd,
            )
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
