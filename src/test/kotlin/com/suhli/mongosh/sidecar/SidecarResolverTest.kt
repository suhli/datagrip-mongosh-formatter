package com.suhli.mongosh.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

class SidecarResolverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extracts platform binary once and resolves launch args`() {
        val resources = resourcesFor(
            executableBytes = "binary".toByteArray(),
            formatterJs = "js".toByteArray(),
            mode = "interpreter",
        )
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> resources[path]?.inputStream() },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        val first = resolver.resolve()
        val second = resolver.resolve()
        assertEquals(first.executable, second.executable)
        assertTrue(Files.isRegularFile(first.executable))
        assertEquals("binary", Files.readString(first.executable))
        assertTrue(first.args.single().endsWith("formatter.js"))
        assertTrue(first.executable.toString().contains(resources.contentHash))
    }

    @Test
    fun `missing platform resource is unsupported`() {
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
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
    fun `content hash change uses a new cache directory`() {
        val v1 = resourcesFor("old".toByteArray(), "js".toByteArray(), "interpreter")
        val v2 = resourcesFor("new".toByteArray(), "js".toByteArray(), "interpreter")
        val extractor1 = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> v1[path]?.inputStream() },
        )
        val extractor2 = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> v2[path]?.inputStream() },
        )
        val old = SidecarResolver(extractor1, HostPlatform(OsFamily.WINDOWS, Arch.X64)).resolve()
        val fresh = SidecarResolver(extractor2, HostPlatform(OsFamily.WINDOWS, Arch.X64)).resolve()
        assertEquals("old", Files.readString(old.executable))
        assertEquals("new", Files.readString(fresh.executable))
        assertTrue(old.executable.toString().contains(v1.contentHash))
        assertTrue(fresh.executable.toString().contains(v2.contentHash))
        assertTrue(v1.contentHash != v2.contentHash)
    }

    @Test
    fun `valid cache hit reuses extracted files`() {
        val resources = resourcesFor("binary".toByteArray(), "js".toByteArray(), "interpreter")
        var opens = 0
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path ->
                opens += 1
                resources[path]?.inputStream()
            },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        resolver.resolve()
        val opensAfterFirst = opens
        resolver.resolve()
        // Second resolve should only re-open the classpath manifest, not re-extract binaries.
        assertTrue(opens > opensAfterFirst)
        assertTrue(opens - opensAfterFirst <= 2)
    }

    @Test
    fun `missing formatter js forces re-extract`() {
        val resources = resourcesFor("binary".toByteArray(), "js".toByteArray(), "interpreter")
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> resources[path]?.inputStream() },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        val first = resolver.resolve()
        Files.delete(first.workingDirectory.resolve("formatter.js"))
        val second = resolver.resolve()
        assertTrue(Files.isRegularFile(second.workingDirectory.resolve("formatter.js")))
    }

    @Test
    fun `hash mismatch forces re-extract`() {
        val resources = resourcesFor("binary".toByteArray(), "js".toByteArray(), "interpreter")
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> resources[path]?.inputStream() },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        val first = resolver.resolve()
        Files.writeString(first.executable, "tampered")
        val second = resolver.resolve()
        assertEquals("binary", Files.readString(second.executable))
    }

    @Test
    fun `missing executable forces re-extract`() {
        val resources = resourcesFor("binary".toByteArray(), "js".toByteArray(), "interpreter")
        val extractor = SidecarExtractor(
            cacheRoot = temp.root.toPath(),
            resourceOpener = { path -> resources[path]?.inputStream() },
        )
        val resolver = SidecarResolver(extractor, HostPlatform(OsFamily.WINDOWS, Arch.X64))
        val first = resolver.resolve()
        Files.delete(first.executable)
        val second = resolver.resolve()
        assertTrue(Files.isRegularFile(second.executable))
        assertEquals("binary", Files.readString(second.executable))
    }

    private data class ResourceBundle(
        private val map: Map<String, ByteArray>,
        val contentHash: String,
    ) : Map<String, ByteArray> by map

    private fun resourcesFor(
        executableBytes: ByteArray,
        formatterJs: ByteArray,
        mode: String,
    ): ResourceBundle {
        val launch = """{"mode":"$mode","args":["formatter.js"]}""".toByteArray(StandardCharsets.UTF_8)
        val required = listOf(
            "formatter.js" to formatterJs,
            "launch.json" to launch,
            "mongosh-formatter.exe" to executableBytes,
        ).sortedBy { it.first }
        val requiredJson = required.joinToString(",") { (name, bytes) ->
            """{"name":"$name","sha256":"${sha256(bytes)}"}"""
        }
        val contentHash = sha256(
            required.joinToString("\n") { (name, bytes) -> "$name:${sha256(bytes)}" }
                .toByteArray(StandardCharsets.UTF_8),
        )
        val manifest = """
            {
              "protocolVersion": 1,
              "runtime": "quickjs-ng",
              "runtimeVersion": "0.16.2",
              "prettierVersion": "3.9.6",
              "mode": "$mode",
              "contentHash": "$contentHash",
              "requiredFiles": [$requiredJson]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val map = mapOf(
            "/sidecar/windows-x64/mongosh-formatter.exe" to executableBytes,
            "/sidecar/windows-x64/formatter.js" to formatterJs,
            "/sidecar/windows-x64/launch.json" to launch,
            "/sidecar/windows-x64/manifest.json" to manifest,
        )
        return ResourceBundle(map, contentHash)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
