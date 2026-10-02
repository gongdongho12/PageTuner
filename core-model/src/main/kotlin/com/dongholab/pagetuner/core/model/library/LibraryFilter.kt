package com.dongholab.pagetuner.core.model.library

/** Whole-library predicates, applied before paging. Null means no predicate; an empty folder means unfiled. */
data class LibraryFilter(
    val q: String? = null,
    val folder: String? = null,
    val tag: String? = null,
    val favorite: Boolean? = null,
) {
    fun validate() {
        require(q == null || canonical(q, 0, 200)) { "Invalid library search." }
        require(folder == null || canonical(folder, 0, 200)) { "Invalid library folder filter." }
        require(tag == null || canonical(tag, 1, 60)) { "Invalid library tag filter." }
    }

    companion object {
        /** Matches ECMAScript String.trim, including NBSP and BOM, on both clients. */
        fun trim(value: String): String = value.trim(::trimCharacter)

        private fun canonical(value: String, minimum: Int, maximum: Int): Boolean {
            if (value.length !in minimum..maximum || value.any(Char::isISOControl) || trim(value) != value) return false
            var offset = 0
            while (offset < value.length) {
                val character = value[offset++]
                if (Character.isHighSurrogate(character)) {
                    if (offset == value.length || !Character.isLowSurrogate(value[offset++])) return false
                } else if (Character.isLowSurrogate(character)) return false
            }
            return true
        }

        private fun trimCharacter(value: Char): Boolean = value in '\u0009'..'\u000d' || value == '\u0020' ||
            value == '\u00a0' || value == '\u1680' || value in '\u2000'..'\u200a' || value in '\u2028'..'\u2029' ||
            value == '\u202f' || value == '\u205f' || value == '\u3000' || value == '\ufeff'
    }
}
