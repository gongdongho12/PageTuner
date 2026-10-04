package com.dongholab.pagetuner.translation

import org.json.JSONObject
import org.json.JSONTokener

/** Android's org.json accepts non-JSON syntax and trailing data; validate the complete wire value first. */
internal fun translationJsonObject(value: String, providerName: String): JSONObject = try {
    TranslationJsonSyntax(value).validate()
    JSONObject(value)
} catch (_: Exception) {
    // Parser diagnostics can include source text or credentials echoed by a provider.
    throw providerResponseFormatException(providerName, "Provider response was not a complete valid JSON object.")
}

private class TranslationJsonSyntax(private val text: String) {
    private var offset = 0

    fun validate() {
        require(text.length <= 4 * 1024 * 1024)
        whitespace()
        require(peek() == '{')
        value(0)
        whitespace()
        require(offset == text.length)
    }

    private fun value(depth: Int) {
        whitespace()
        when (peek()) {
            '{' -> {
                require(depth < 32)
                offset++; whitespace()
                val keys = hashSetOf<String>()
                if (take('}')) return
                do {
                    whitespace()
                    require(keys.add(string()))
                    whitespace(); require(take(':'))
                    value(depth + 1); whitespace()
                    if (take('}')) return
                    require(take(','))
                } while (true)
            }
            '[' -> {
                require(depth < 32)
                offset++; whitespace()
                if (take(']')) return
                do {
                    value(depth + 1); whitespace()
                    if (take(']')) return
                    require(take(','))
                } while (true)
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val match = Number.find(text, offset)
                require(match != null && match.range.first == offset)
                offset = match.range.last + 1
            }
        }
    }

    private fun string(): String {
        val start = offset
        require(take('"'))
        while (offset < text.length) {
            val next = text[offset++]
            if (next == '"') {
                val decoded = JSONTokener(text.substring(start, offset)).nextValue() as String
                var index = 0
                while (index < decoded.length) {
                    val character = decoded[index++]
                    if (character.isHighSurrogate()) require(index < decoded.length && decoded[index++].isLowSurrogate())
                    else require(!character.isLowSurrogate())
                }
                return decoded
            }
            require(next >= ' ')
            if (next == '\\') {
                require(offset < text.length)
                when (text[offset++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> {
                        require(offset + 4 <= text.length && text.substring(offset, offset + 4).all { it in "0123456789abcdefABCDEF" })
                        offset += 4
                    }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun literal(expected: String) { require(text.startsWith(expected, offset)); offset += expected.length }
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
    private fun peek(): Char? = text.getOrNull(offset)
    private fun take(expected: Char): Boolean = (peek() == expected).also { if (it) offset++ }
    private companion object { val Number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
