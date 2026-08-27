package com.suhli.mongosh.formatter

import java.time.Duration

/**
 * Size / timeout policy for MongoJS formatting.
 *
 * Benchmarks (QuickJS-ng + Prettier Standalone, one process per request) show
 * ~6s for 100 KB and ~74s for 1 MB. Waiting a minute is not acceptable in the
 * Mongo Console, so oversized documents are rejected immediately.
 */
object FormatSizePolicy {
    /** Inputs at or below this size use the base sidecar timeout. */
    const val SAFE_SOURCE_CHARS = 256 * 1024

    /** Hard upper bound — reject without starting the sidecar. */
    const val MAX_SOURCE_CHARS = 512 * 1024

    /** AsyncDocumentFormattingService timeout. Must stay above sidecar upper bound. */
    val FORMATTER_TIMEOUT: Duration = Duration.ofSeconds(45)

    private val SIDECAR_BASE = Duration.ofSeconds(8)
    private val SIDECAR_MAX = Duration.ofSeconds(35)
    private const val PER_KB_MS = 50L

    fun rejectMessage(sourceLength: Int): String {
        val kb = (sourceLength + 512) / 1024
        return "MongoJS formatting skipped: document is too large ($kb KB)."
    }

    fun isTooLarge(sourceLength: Int): Boolean = sourceLength > MAX_SOURCE_CHARS

    /**
     * Sidecar process timeout derived from input size.
     * Always strictly less than [FORMATTER_TIMEOUT] so cleanup / JSON parse /
     * error reporting still have headroom.
     */
    fun sidecarTimeout(sourceLength: Int): Duration {
        val scaledMs = SIDECAR_BASE.toMillis() + (sourceLength / 1024L) * PER_KB_MS
        val capped = scaledMs.coerceIn(SIDECAR_BASE.toMillis(), SIDECAR_MAX.toMillis())
        check(capped < FORMATTER_TIMEOUT.toMillis()) {
            "sidecar timeout must stay below formatter timeout"
        }
        return Duration.ofMillis(capped)
    }
}
