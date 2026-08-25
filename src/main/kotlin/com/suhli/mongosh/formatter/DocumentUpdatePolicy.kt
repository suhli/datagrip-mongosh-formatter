package com.suhli.mongosh.formatter

object DocumentUpdatePolicy {
    fun nextText(original: String, result: FormatResult): String? {
        return when (result) {
            is FormatResult.Success -> result.formatted.takeIf { it != original }
            is FormatResult.Failure -> null
        }
    }
}
