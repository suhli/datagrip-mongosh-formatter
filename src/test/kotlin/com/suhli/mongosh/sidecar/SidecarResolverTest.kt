package com.suhli.mongosh.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SidecarResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extracts platform binary once and resolves launch args`() {
        val resources = mapOf(
            "/sidecar/windows-x64/mongosh-formatter.exe" to "binary".toByteArray(),
            "/sidecar/windows-x64/formatter.js" to "js".toByteArray(),
            "/sidecar/windows-x64/launch.json" to """{"mode":"interpreter","args":["formatter.js"]}""".toByteArray(),
        )
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            sidecarVersion = "test-1",
            resourceOpener = { path -> resources[path]?.inputStream() },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        val first = resolver.resolve()
        val second = resolver.resolve()
        assertEquals(first.executable, second.executable)
        assertTrue(Files.isRegularFile(first.executable))
        assertEquals("binary", Files.readString(first.executable))
        assertTrue(first.args.single().endsWith("formatter.js"))
    }

    @Test
    fun `missing platform resource is unsupported`() {
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            sidecarVersion = "test-1",
            resourceOpener = { null },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.LINUX, Arch.X64))
        try {
            resolver.resolve()
            throw AssertionError("expected UnsupportedPlatformException")
        } catch (_: UnsupportedPlatformException) {
        }
    }

    @Test
    fun `upgrade uses a new version directory`() {
        val v1 = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            sidecarVersion = "v1",
            resourceOpener = {
                if (it.endsWith("mongosh-formatter.exe")) "old".toByteArray().inputStream() else null
            },
        )
        val v2 = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            sidecarVersion = "v2",
            resourceOpener = {
                if (it.endsWith("mongosh-formatter.exe")) "new".toByteArray().inputStream() else null
            },
        )
        val old = SidecarResolver(v1, HostPlatform(OsFamily.WINDOWS, Arch.X64)).resolve()
        val fresh = SidecarResolver(v2, HostPlatform(OsFamily.WINDOWS, Arch.X64)).resolve()
        assertEquals("old", Files.readString(old.executable))
        assertEquals("new", Files.readString(fresh.executable))
        assertTrue(old.executable.toString().contains("v1"))
        assertTrue(fresh.executable.toString().contains("v2"))
    }
}
