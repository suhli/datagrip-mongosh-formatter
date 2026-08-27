package com.suhli.mongosh.sidecar

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.suhli.mongosh.formatter.FormatRequest
import com.suhli.mongosh.formatter.FormatResult
import com.suhli.mongosh.formatter.FormatSizePolicy
import com.suhli.mongosh.formatter.FormatterOptions

object SidecarProtocol {
    const val VERSION = 1
    const val MAX_SOURCE_CHARS = FormatSizePolicy.MAX_SOURCE_CHARS

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun encode(request: FormatRequest): String {
        val json = JsonObject()
        json.addProperty("protocolVersion", VERSION)
        json.addProperty("source", request.source)
        request.rangeStart?.let { json.addProperty("rangeStart", it) }
        request.rangeEnd?.let { json.addProperty("rangeEnd", it) }
        request.cursorOffset?.let { json.addProperty("cursorOffset", it) }
        json.add("options", encodeOptions(request.options))
        return gson.toJson(json)
    }

    fun decode(stdout: String): FormatResult {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) {
            return FormatResult.Failure("Sidecar returned an empty response")
        }
        val parsed = try {
            JsonParser.parseString(trimmed)
        } catch (_: Exception) {
            return FormatResult.Failure("Sidecar returned invalid JSON")
        }
        if (!parsed.isJsonObject) {
            return FormatResult.Failure("Sidecar returned invalid JSON")
        }
        val root = parsed.asJsonObject
        val protocolVersion = root.get("protocolVersion")?.asInt
        if (protocolVersion != VERSION) {
            return FormatResult.Failure("Sidecar protocol mismatch: $protocolVersion")
        }
        val ok = root.get("ok")?.asBoolean ?: false
        if (ok) {
            val formatted = root.get("formatted")?.asString
                ?: return FormatResult.Failure("Sidecar success response is missing formatted text")
            val cursor = root.get("cursorOffset")?.takeIf { it.isJsonPrimitive }?.asInt
            return FormatResult.Success(formatted, cursor)
        }
        val error = root.getAsJsonObject("error")
        return FormatResult.Failure(
            message = error?.get("message")?.asString ?: "Formatting failed",
            line = error?.get("line")?.takeIf { it.isJsonPrimitive }?.asInt,
            column = error?.get("column")?.takeIf { it.isJsonPrimitive }?.asInt,
        )
    }

    private fun encodeOptions(options: FormatterOptions): JsonObject {
        val json = JsonObject()
        json.addProperty("printWidth", options.printWidth)
        json.addProperty("tabWidth", options.tabWidth)
        json.addProperty("useTabs", options.useTabs)
        json.addProperty("semi", options.semi)
        json.addProperty("singleQuote", options.singleQuote)
        json.addProperty("trailingComma", options.trailingComma)
        json.addProperty("bracketSpacing", options.bracketSpacing)
        json.addProperty("endOfLine", options.endOfLine)
        return json
    }
}
