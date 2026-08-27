package com.suhli.mongosh.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentUpdatePolicyTest {
    @Test
    fun `success replaces the document once`() {
        val next = DocumentUpdatePolicy.nextText("db.x.find({a:1})", FormatResult.Success("db.x.find({ a: 1 });\n"))
        assertEquals("db.x.find({ a: 1 });\n", next)
    }

    @Test
    fun `identical success is a no-op`() {
        val source = "db.x.find({ a: 1 });\n"
        assertNull(DocumentUpdatePolicy.nextText(source, FormatResult.Success(source)))
    }

    @Test
    fun `failure never changes the document`() {
        val source = "db.users.find({"
        assertNull(DocumentUpdatePolicy.nextText(source, FormatResult.Failure("Unexpected token", 1, 16)))
    }

    @Test
    fun `ranged success that preserves outside selection is applied`() {
        val source = "db.keep.find({z:9})\ndb.users.find({a:1})\n"
        val formatted = "db.keep.find({z:9})\ndb.users.find({ a: 1 });\n"
        assertEquals(
            formatted,
            DocumentUpdatePolicy.nextText(
                original = source,
                result = FormatResult.Success(formatted),
                rangeStart = 20,
                rangeEnd = 40,
            ),
        )
    }

    @Test
    fun `whole document rewrite for a small selection is refused`() {
        val source = "db.keep.find({z:9})\ndb.users.find({a:1})\ndb.other.find({b:2})\n"
        // Pretend a full-buffer prettier run compacted everything.
        val whole = "db.keep.find({ z: 9 });\ndb.users.find({ a: 1 });\ndb.other.find({ b: 2 });\n"
        assertNull(
            DocumentUpdatePolicy.nextText(
                original = source,
                result = FormatResult.Success(whole),
                rangeStart = 20,
                rangeEnd = 40,
            ),
        )
    }

    @Test
    fun `looksLikeWholeDocumentRewrite detects full buffer rewrites`() {
        val source = "aaa\nSELECTED\nbbb\nccc\n"
        val whole = "aaa;\nSELECTED;\nbbb;\nccc;\n"
        assertTrue(DocumentUpdatePolicy.looksLikeWholeDocumentRewrite(source, whole, 4, 12))
        assertFalse(
            DocumentUpdatePolicy.looksLikeWholeDocumentRewrite(
                source,
                "aaa\nSELECTED_FMT\nbbb\nccc\n",
                4,
                12,
            ),
        )
    }
}
