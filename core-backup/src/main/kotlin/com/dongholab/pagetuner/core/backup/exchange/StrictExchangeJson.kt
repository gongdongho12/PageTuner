package com.dongholab.pagetuner.core.backup.exchange

/** org.json differs across Android/JVM; accept the same strict JSON grammar on both. */
internal object StrictExchangeJson {
    fun validate(text: String) = Parser(text).validate()
    fun normalize(text: String): String = Parser(text).normalize()

    private class Parser(private val text: String) {
        private var cursor = 0
        private val numbers = mutableListOf<Triple<Int, Int, String>>()
        fun normalize(): String {
            validate()
            if (numbers.isEmpty()) return text
            return buildString {
                var copied = 0
                numbers.forEach { (start, end, value) -> append(text, copied, start); append(value); copied = end }
                append(text, copied, text.length)
            }
        }
        fun validate() {
            whitespace(); require(peek() == '{') { "JSON root must be an object." }
            value(0); whitespace(); require(cursor == text.length) { "Trailing JSON data." }
        }
        private fun value(depth: Int) {
            require(depth <= 32) { "JSON is nested too deeply." }
            whitespace()
            when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> string(false)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> error("Invalid JSON value.")
            }
        }
        private fun objectValue(depth: Int) {
            require(depth < 32) { "JSON is nested too deeply." }
            cursor++; whitespace()
            if (take('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                require(peek() == '"') { "JSON keys must be quoted." }
                require(keys.add(string(true))) { "Duplicate JSON key." }
                whitespace(); require(take(':')) { "Missing JSON colon." }; value(depth + 1); whitespace()
                if (take('}')) return
                require(take(',')) { "Missing JSON comma." }; whitespace()
            }
        }
        private fun arrayValue(depth: Int) {
            require(depth < 32) { "JSON is nested too deeply." }
            cursor++; whitespace()
            if (take(']')) return
            while (true) {
                value(depth + 1); whitespace()
                if (take(']')) return
                require(take(',')) { "Missing JSON comma." }; whitespace()
            }
        }
        private fun string(capture: Boolean): String {
            require(take('"')) { "Missing JSON quote." }
            val result = if (capture) StringBuilder() else null
            while (cursor < text.length) {
                val char = text[cursor++]
                if (char == '"') return result?.toString().orEmpty()
                require(char.code >= 0x20) { "Unescaped JSON control character." }
                if (char != '\\') result?.append(char) else {
                    require(cursor < text.length) { "Incomplete JSON escape." }
                    val escaped = when (val escape = text[cursor++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            require(cursor + 4 <= text.length) { "Incomplete JSON Unicode escape." }
                            val hex = text.substring(cursor, cursor + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid JSON Unicode escape." }
                            cursor += 4; hex.toInt(16).toChar()
                        }
                        else -> error("Invalid JSON escape.")
                    }
                    result?.append(escaped)
                }
            }
            error("Unterminated JSON string.")
        }
        private fun number() {
            val start = cursor
            take('-')
            if (!take('0')) { require(peek() in '1'..'9') { "Invalid JSON number." }; digits() }
            if (take('.')) { require(peek() in '0'..'9') { "Missing JSON fraction." }; digits() }
            if (peek() == 'e' || peek() == 'E') {
                cursor++; if (peek() == '+' || peek() == '-') cursor++
                require(peek() in '0'..'9') { "Missing JSON exponent." }; digits()
            }
            val token = text.substring(start, cursor)
            val number = token.toDoubleOrNull() ?: error("Invalid binary64 JSON number.")
            require(number.isFinite()) { "Non-finite JSON number." }
            require(number % 1.0 != 0.0 || kotlin.math.abs(number) <= 9_007_199_254_740_991.0) { "JSON integer exceeds the portable safe range." }
            val mantissa = token.substringBefore('e').substringBefore('E')
            require(number != 0.0 || mantissa.none { it in '1'..'9' }) { "Nonzero JSON number underflows binary64." }
            // Match JavaScript number semantics before org.json can retain arbitrary decimal precision.
            numbers.add(Triple(start, cursor, if (number == 0.0) "0" else number.toString()))
        }
        private fun digits() { while (peek() in '0'..'9') cursor++ }
        private fun literal(value: String) { require(text.startsWith(value, cursor)) { "Invalid JSON literal." }; cursor += value.length }
        private fun whitespace() { while (peek() in listOf(' ', '\t', '\n', '\r')) cursor++ }
        private fun peek(): Char = text.getOrNull(cursor) ?: '\u0000'
        private fun take(char: Char): Boolean = if (peek() == char) { cursor++; true } else false
    }
}
