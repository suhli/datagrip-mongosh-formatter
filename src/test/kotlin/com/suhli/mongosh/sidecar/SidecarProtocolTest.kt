package com.suhli.mongosh.sidecar

import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import com.suhli.mongosh.formatter.FormatterOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarProtocolTest {
    @Test
    fun `encodes full document request`() {
        val json = SidecarProtocol.encode(
            FormatRequest(
                source = "db.users.find({a:1})",
                cursorOffset = 3,
                options = FormatterOptions(printWidth = 80, tabWidth = 4),
            ),
        )
        assertTrue(json.contains("\"protocolVersion\":1"))
        assertTrue(json.contains("\"source\":\"db.users.find({a:1})\""))
        assertTrue(json.contains("\"cursorOffset\":3"))
        assertTrue(json.contains("\"printWidth\":80"))
        assertTrue(!json.contains("rangeStart"))
    }

    @Test
    fun `encodes selection as document offsets`() {
        val json = SidecarProtocol.encode(
            FormatRequest(
                source = "db.users.find({a:1,b:2})",
                rangeStart = 14,
                rangeEnd = 23,
                cursorOffset = 20,
            ),
        )
        assertTrue(json.contains("\"rangeStart\":14"))
        assertTrue(json.contains("\"rangeEnd\":23"))
    }

    @Test
    fun `encodes unicode without html escaping`() {
        val json = SidecarProtocol.encode(FormatRequest(source = "db.x.find({name:\"中文😀\"})"))
        assertTrue(json.contains("中文😀"))
    }

    @Test
    fun `decodes success`() {
        val result = SidecarProtocol.decode(
            """{"protocolVersion":1,"ok":true,"formatted":"db.x.find();\n","cursorOffset":8}""",
        ) as FormatResult.Success
        assertEquals("db.x.find();\n", result.formatted)
        assertEquals(8, result.cursorOffset)
    }

    @Test
    fun `decodes failure`() {
        val result = SidecarProtocol.decode(
            """{"protocolVersion":1,"ok":false,"error":{"message":"Unexpected token","line":3,"column":8}}""",
        ) as FormatResult.Failure
        assertEquals("Unexpected token", result.message)
        assertEquals(3, result.line)
        assertEquals(8, result.column)
    }

    @Test
    fun `malformed response is a failure`() {
        val result = SidecarProtocol.decode("not-json") as FormatResult.Failure
        assertTrue(result.message.contains("invalid JSON"))
    }

    @Test
    fun `empty response is a failure`() {
        val result = SidecarProtocol.decode("   ") as FormatResult.Failure
        assertTrue(result.message.contains("empty"))
    }

    @Test
    fun `protocol mismatch is a failure`() {
        val result = SidecarProtocol.decode("""{"protocolVersion":99,"ok":true,"formatted":"x"}""") as FormatResult.Failure
        assertTrue(result.message.contains("protocol"))
    }
}
