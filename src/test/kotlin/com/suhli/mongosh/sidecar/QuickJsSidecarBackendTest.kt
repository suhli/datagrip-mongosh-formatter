package com.suhli.mongosh.sidecar

import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class QuickJsSidecarBackendTest {
    @Test
    fun `formats a mongo find query`() {
        val backend = liveBackend()
        val result = backend.format(FormatRequest(source = "db.users.find({a:1,b:{\$gt:2}})")) as FormatResult.Success
        assertTrue(result.formatted.contains("db.users.find"))
        assertTrue(result.formatted.contains("\$gt"))
    }

    @Test
    fun `formats aggregate objectid and isodate`() {
        val backend = liveBackend()
        val source = """
            use("test")
            db.users.aggregate([{${'$'}match:{status:"active"}}])
            db.users.find({_id:ObjectId("64f000000000000000000000")})
            db.logs.find({createdAt:{${'$'}gte:ISODate("2026-01-01T00:00:00Z")}})
        """.trimIndent()
        val result = backend.format(FormatRequest(source = source))
        assertTrue(result is FormatResult.Success)
        val formatted = (result as FormatResult.Success).formatted
        assertTrue(formatted.contains("use("))
        assertTrue(formatted.contains("ObjectId("))
        assertTrue(formatted.contains("ISODate("))
    }

    @Test
    fun `range formatting uses document offsets`() {
        val backend = liveBackend()
        val source = "db.keep.find({z:9})\ndb.users.find({a:1,b:2})\n"
        val result = backend.format(
            FormatRequest(
                source = source,
                rangeStart = 20,
                rangeEnd = 43,
                cursorOffset = 32,
            ),
        ) as FormatResult.Success
        assertTrue(result.formatted.contains("db.keep.find({z:9})"))
        assertTrue(result.formatted.contains("db.users.find"))
    }

    @Test
    fun `syntax error does not return formatted text`() {
        val backend = liveBackend()
        val result = backend.format(FormatRequest(source = "db.users.find({")) as FormatResult.Failure
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `unicode and emoji offsets stay in the formatted document`() {
        val backend = liveBackend()
        val source = "db.users.find({name:\"中文😀\"})"
        val emojiAt = source.indexOf("😀")
        val result = backend.format(
            FormatRequest(source = source, cursorOffset = emojiAt),
        ) as FormatResult.Success
        assertTrue(result.formatted.contains("中文😀"))
        assertTrue(result.cursorOffset != null)
        assertTrue(result.formatted.substring(result.cursorOffset!!).contains("😀") || result.formatted.contains("😀"))
    }

    @Test
    fun `crlf documents keep crlf when requested`() {
        val backend = liveBackend()
        val source = "db.users.find({a:1,b:2})\r\ndb.logs.find({c:3})\r\n"
        val result = backend.format(
            FormatRequest(
                source = source,
                options = com.suhli.mongosh.formatter.FormatterOptions(endOfLine = "crlf"),
            ),
        ) as FormatResult.Success
        assertTrue(result.formatted.contains("\r\n"))
    }

    private fun liveBackend(): QuickJsSidecarBackend {
        val spec = liveSpec()
        assumeTrue("native sidecar is required", spec != null)
        return QuickJsSidecarBackend(
            resolver = { spec!! },
            client = SidecarProcessClient(timeout = Duration.ofSeconds(30)),
        )
    }

    private fun liveSpec(): SidecarLaunchSpec? {
        val dirProperty = System.getProperty("sidecar.dir")
        val dir = when {
            !dirProperty.isNullOrBlank() -> Path.of(dirProperty)
            else -> Path.of("sidecar", "dist", "native", HostPlatform.current().resourceDir)
        }
        val executable = dir.resolve(HostPlatform.current().executableName)
        if (!Files.isRegularFile(executable)) {
            return null
        }
        val script = dir.resolve("formatter.js")
        val args = if (Files.isRegularFile(script)) listOf(script.toString()) else emptyList()
        return SidecarLaunchSpec(executable, args, dir)
    }
}
