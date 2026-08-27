package com.suhli.mongosh.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class FormatSizePolicyTest {
    @Test
    fun `small documents are accepted`() {
        assertFalse(FormatSizePolicy.isTooLarge(1024))
        assertTrue(FormatSizePolicy.sidecarTimeout(1024) < FormatSizePolicy.FORMATTER_TIMEOUT)
    }

    @Test
    fun `medium documents stay under formatter timeout`() {
        val timeout = FormatSizePolicy.sidecarTimeout(FormatSizePolicy.SAFE_SOURCE_CHARS)
        assertTrue(timeout.toMillis() < FormatSizePolicy.FORMATTER_TIMEOUT.toMillis())
        assertTrue(timeout.toMillis() >= Duration.ofSeconds(8).toMillis())
    }

    @Test
    fun `too large documents are rejected immediately`() {
        assertTrue(FormatSizePolicy.isTooLarge(FormatSizePolicy.MAX_SOURCE_CHARS + 1))
        assertTrue(FormatSizePolicy.rejectMessage(742 * 1024).contains("742 KB"))
    }

    @Test
    fun `sidecar timeout never reaches formatter timeout`() {
        val atMax = FormatSizePolicy.sidecarTimeout(FormatSizePolicy.MAX_SOURCE_CHARS)
        assertTrue(atMax < FormatSizePolicy.FORMATTER_TIMEOUT)
        assertTrue(atMax.toSeconds() <= 35)
    }
}

class TargetEditorResolverTest {
    @Test
    fun `same document split editors pick the selected one with selection`() {
        val doc = Any()
        val editors = listOf(
            TargetEditorResolver.EditorRef("A", doc, 100, hasSelection = false, isSelectedTextEditor = false),
            TargetEditorResolver.EditorRef("B", doc, 100, hasSelection = true, isSelectedTextEditor = true),
        )
        assertEquals("B", TargetEditorResolver.resolve(doc, editors))
    }

    @Test
    fun `other document with same textLength is ignored`() {
        val target = Any()
        val other = Any()
        val editors = listOf(
            TargetEditorResolver.EditorRef("other", other, 100, hasSelection = true, isSelectedTextEditor = true),
        )
        assertNull(TargetEditorResolver.resolve(target, editors))
        assertFalse(TargetEditorResolver.accepts(other, target, 100, 100))
    }

    @Test
    fun `other document with active selection is ignored`() {
        val target = Any()
        val other = Any()
        val editors = listOf(
            TargetEditorResolver.EditorRef("mine", target, 50, hasSelection = false, isSelectedTextEditor = false),
            TargetEditorResolver.EditorRef("other", other, 50, hasSelection = true, isSelectedTextEditor = true),
        )
        assertEquals("mine", TargetEditorResolver.resolve(target, editors))
    }

    @Test
    fun `no reliable target editor returns null`() {
        assertNull(TargetEditorResolver.resolve(Any(), emptyList()))
    }
}

class CaretRestorePolicyTest {
    @Test
    fun `normal selection keeps anchor before lead`() {
        val snapshot = CaretSnapshot(
            targetEditorId = "ed",
            caretOffset = 40,
            hasSelection = true,
            selectionStart = 10,
            selectionEnd = 40,
            selectionAnchor = 10,
        )
        val plan = CaretRestorePolicy.plan(snapshot, mappedCaret = 35, documentLength = 100)!!
        assertTrue(plan.hasSelection)
        assertEquals(10, plan.anchorOffset)
        assertEquals(35, plan.leadOffset)
    }

    @Test
    fun `reverse selection keeps anchor after lead`() {
        val snapshot = CaretSnapshot(
            targetEditorId = "ed",
            caretOffset = 100,
            hasSelection = true,
            selectionStart = 100,
            selectionEnd = 200,
            selectionAnchor = 200,
        )
        val plan = CaretRestorePolicy.plan(snapshot, mappedCaret = 100, documentLength = 300)!!
        assertTrue(plan.hasSelection)
        assertEquals(200, plan.anchorOffset)
        assertEquals(100, plan.leadOffset)
        assertTrue(plan.anchorOffset > plan.leadOffset)
    }

    @Test
    fun `no selection only restores caret`() {
        val snapshot = CaretSnapshot(
            targetEditorId = "ed",
            caretOffset = 12,
            hasSelection = false,
            selectionStart = 12,
            selectionEnd = 12,
            selectionAnchor = 12,
        )
        val plan = CaretRestorePolicy.plan(snapshot, mappedCaret = 20, documentLength = 50)!!
        assertFalse(plan.hasSelection)
        assertEquals(20, plan.caretOffset)
    }
}
