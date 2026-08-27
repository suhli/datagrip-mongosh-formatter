package com.suhli.mongosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards against logging Mongo query / selection bodies into idea.log.
 */
class LoggingSafetyTest {
    @Test
    fun `formatting service source does not log document snippets`() {
        val path = Path.of("src/main/kotlin/com/suhli/mongosh/MongoJsFormattingService.kt")
        val text = Files.readString(path)
        assertFalse("snippet helper must stay deleted", text.contains("fun snippet"))
        assertFalse(text.contains("snippet='"))
        assertFalse(text.contains("editor.document.text,"))
        assertFalse(text.contains("editor.document.text)"))
        assertTrue(text.contains("sourceLength="))
    }

    @Test
    fun `sidecar backend logs metadata only`() {
        val path = Path.of("src/main/kotlin/com/suhli/mongosh/sidecar/QuickJsSidecarBackend.kt")
        val text = Files.readString(path)
        assertFalse(text.contains("\${request.source}"))
        assertFalse(text.contains("\${stdin}"))
        assertTrue(text.contains("inputLength="))
        assertTrue(text.contains("durationMs="))
    }
}
