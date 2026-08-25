package com.suhli.mongosh.formatter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
