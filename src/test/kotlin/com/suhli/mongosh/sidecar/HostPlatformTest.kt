package com.suhli.mongosh.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostPlatformTest {
    @Test
    fun `windows amd64`() {
        val platform = HostPlatform.from("Windows 11", "amd64")
        assertEquals("windows-x64", platform.resourceDir)
        assertEquals("mongosh-formatter.exe", platform.executableName)
    }

    @Test
    fun `windows arm64 uses x64 binary`() {
        val platform = HostPlatform.from("Windows 11", "aarch64")
        assertEquals("windows-x64", platform.resourceDir)
    }

    @Test
    fun `linux x86_64`() {
        val platform = HostPlatform.from("Linux", "x86_64")
        assertEquals("linux-x64", platform.resourceDir)
        assertEquals("mongosh-formatter", platform.executableName)
    }

    @Test
    fun `linux aarch64`() {
        val platform = HostPlatform.from("Linux", "aarch64")
        assertEquals("linux-aarch64", platform.resourceDir)
    }

    @Test
    fun `macos intel`() {
        val platform = HostPlatform.from("Mac OS X", "x86_64")
        assertEquals("macos-x64", platform.resourceDir)
    }

    @Test
    fun `macos apple silicon`() {
        val platform = HostPlatform.from("Mac OS X", "aarch64")
        assertEquals("macos-aarch64", platform.resourceDir)
    }

    @Test
    fun `unknown os is rejected`() {
        assertThrows(UnsupportedPlatformException::class.java) {
            HostPlatform.from("OS/2", "amd64")
        }
    }

    @Test
    fun `unknown arch is rejected`() {
        assertThrows(UnsupportedPlatformException::class.java) {
            HostPlatform.from("Linux", "riscv64")
        }
    }
}
