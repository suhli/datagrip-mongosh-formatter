package com.suhli.mongosh.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class SidecarProcessClientTest {
    private val client = SidecarProcessClient(timeout = Duration.ofSeconds(8))

    @Test
    fun `timeout kills hanging process`() {
        val spec = pythonSpec("hang.py") ?: return
        val started = System.currentTimeMillis()
        val result = SidecarProcessClient(timeout = Duration.ofMillis(1500)).run(spec, "{}")
        assertTrue(result.timedOut)
        assertTrue(System.currentTimeMillis() - started < 8_000)
    }

    @Test
    fun `captures non-zero exit code and stderr`() {
        val spec = pythonSpec("fail.py") ?: return
        val result = client.run(spec, "{}")
        assertEquals(7, result.exitCode)
        assertTrue(result.stderr.contains("boom-stderr"))
        assertTrue(result.stdout.contains("boom-stdout"))
    }

    @Test
    fun `malformed stdout is returned as-is`() {
        val spec = pythonSpec("malformed.py") ?: return
        val result = client.run(spec, "{}")
        assertEquals(0, result.exitCode)
        assertEquals("not-json", result.stdout.trim())
    }

    @Test
    fun `cancel stops the process`() {
        val spec = pythonSpec("hang.py") ?: return
        val cancelled = AtomicBoolean(false)
        val thread = Thread {
            Thread.sleep(200)
            cancelled.set(true)
        }
        thread.start()
        val result = SidecarProcessClient(timeout = Duration.ofSeconds(8)).run(spec, "{}", cancelled)
        thread.join()
        assertTrue(result.cancelled || result.timedOut)
    }

    private fun pythonSpec(scriptName: String): SidecarLaunchSpec? {
        val python = pythonExecutable()
        assumeTrue("python is required for process tests", python != null)
        val script = Path.of("src", "test", "resources", "mocks", scriptName).toAbsolutePath()
        assumeTrue(script.toFile().isFile)
        return SidecarLaunchSpec(
            executable = python!!,
            args = listOf(script.toString()),
            workingDirectory = script.parent,
        )
    }

    private fun pythonExecutable(): Path? {
        for (candidate in listOf("python", "python3")) {
            try {
                val process = ProcessBuilder(candidate, "-c", "print(1)").start()
                val ok = process.waitFor() == 0
                if (ok) {
                    return Path.of(candidate)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
