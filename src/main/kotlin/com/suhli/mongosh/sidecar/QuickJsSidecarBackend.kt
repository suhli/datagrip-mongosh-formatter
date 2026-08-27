package com.suhli.mongosh.sidecar

import com.intellij.openapi.diagnostic.Logger
import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import com.suhli.mongosh.formatter.FormatSizePolicy
import com.suhli.mongosh.formatter.FormatterBackend
import java.util.concurrent.atomic.AtomicBoolean

class QuickJsSidecarBackend(
    private val resolver: SidecarSpecProvider,
    private val client: SidecarProcessClient,
) : FormatterBackend {
    override fun format(request: FormatRequest, cancelled: AtomicBoolean): FormatResult {
        if (FormatSizePolicy.isTooLarge(request.source.length)) {
            return FormatResult.Failure(FormatSizePolicy.rejectMessage(request.source.length))
        }
        val spec = try {
            resolver.resolve()
        } catch (error: UnsupportedPlatformException) {
            return FormatResult.Failure(error.message ?: "Unsupported platform")
        }

        val stdin = SidecarProtocol.encode(request)
        val timeout = FormatSizePolicy.sidecarTimeout(request.source.length)
        val started = System.nanoTime()
        val outcome = client.run(spec, stdin, cancelled, timeout)
        val durationMs = (System.nanoTime() - started) / 1_000_000
        LOG.debug(
            "MongoJS sidecar durationMs=$durationMs exitCode=${outcome.exitCode} " +
                "inputLength=${request.source.length} rangeStart=${request.rangeStart} rangeEnd=${request.rangeEnd} " +
                "timeoutMs=${timeout.toMillis()} cancelled=${outcome.cancelled} timedOut=${outcome.timedOut}",
        )

        if (outcome.cancelled) {
            return FormatResult.Failure("Formatting cancelled")
        }
        if (outcome.timedOut) {
            return FormatResult.Failure("Formatter timed out")
        }
        if (outcome.stdout.isBlank()) {
            val detail = outcome.stderr.trim().take(300)
            val suffix = if (detail.isEmpty()) "" else ": $detail"
            return FormatResult.Failure("Sidecar returned no JSON${responseSuffix(outcome, suffix)}")
        }
        return SidecarProtocol.decode(outcome.stdout)
    }

    private fun responseSuffix(outcome: SidecarProcessResult, suffix: String): String {
        return if (outcome.exitCode != null && outcome.exitCode != 0) {
            " (exit ${outcome.exitCode})$suffix"
        } else {
            suffix
        }
    }

    companion object {
        private val LOG = Logger.getInstance(QuickJsSidecarBackend::class.java)
    }
}
