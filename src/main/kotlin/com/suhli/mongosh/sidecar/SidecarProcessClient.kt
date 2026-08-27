package com.suhli.mongosh.sidecar

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class SidecarProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
)

class SidecarProcessClient(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val maxOutputBytes: Int = 16 * 1024 * 1024,
) {
    fun run(
        spec: SidecarLaunchSpec,
        stdin: String,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        timeoutOverride: java.time.Duration? = null,
    ): SidecarProcessResult {
        val effectiveTimeout = timeoutOverride ?: timeout
        val command = ArrayList<String>(1 + spec.args.size)
        command.add(spec.executable.toString())
        command.addAll(spec.args)

        val process = ProcessBuilder(command)
            .directory(spec.workingDirectory.toFile())
            .redirectErrorStream(false)
            .start()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = drain(process.inputStream, stdout)
        val stderrThread = drain(process.errorStream, stderr)

        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(stdin)
            }
            val finished = waitFor(process, cancelled, effectiveTimeout)
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return SidecarProcessResult(
                exitCode = if (finished && !process.isAlive) process.exitValue() else null,
                stdout = stdout.toString(StandardCharsets.UTF_8),
                stderr = stderr.toString(StandardCharsets.UTF_8),
                timedOut = !finished && !cancelled.get(),
                cancelled = cancelled.get(),
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun waitFor(process: Process, cancelled: AtomicBoolean, effectiveTimeout: Duration): Boolean {
        val deadline = System.nanoTime() + effectiveTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (cancelled.get()) {
                process.destroyForcibly()
                return false
            }
            if (!process.isAlive) {
                return true
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                cancelled.set(true)
                return false
            }
        }
        process.destroyForcibly()
        return false
    }

    private fun drain(stream: InputStream, output: ByteArrayOutputStream): Thread {
        val thread = Thread({
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                synchronized(output) {
                    if (output.size() < maxOutputBytes) {
                        val allowed = minOf(read, maxOutputBytes - output.size())
                        output.write(buffer, 0, allowed)
                    }
                }
            }
        }, "mongojs-sidecar-io")
        thread.isDaemon = true
        thread.start()
        return thread
    }
}
