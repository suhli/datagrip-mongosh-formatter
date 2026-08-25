package com.suhli.mongosh.formatter

import java.util.concurrent.atomic.AtomicBoolean

data class FormatRequest(
    val source: String,
    val rangeStart: Int? = null,
    val rangeEnd: Int? = null,
    val cursorOffset: Int? = null,
    val options: FormatterOptions = FormatterOptions(),
)

data class FormatterOptions(
    val printWidth: Int = 100,
    val tabWidth: Int = 2,
    val useTabs: Boolean = false,
    val semi: Boolean = true,
    val singleQuote: Boolean = false,
    val trailingComma: String = "all",
    val bracketSpacing: Boolean = true,
    val endOfLine: String = "auto",
)

sealed class FormatResult {
    data class Success(
        val formatted: String,
        val cursorOffset: Int? = null,
    ) : FormatResult()

    data class Failure(
        val message: String,
        val line: Int? = null,
        val column: Int? = null,
    ) : FormatResult()
}

interface FormatterBackend {
    fun format(request: FormatRequest, cancelled: AtomicBoolean = AtomicBoolean(false)): FormatResult
}
