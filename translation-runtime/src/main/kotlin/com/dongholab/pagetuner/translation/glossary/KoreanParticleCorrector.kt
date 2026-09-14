package com.dongholab.pagetuner.translation.glossary

/**
 * Selects the Korean particle that agrees with the final Hangul syllable of a replaced term.
 *
 * This is intentionally applied only to a particle immediately following a glossary replacement.
 * It therefore fixes output such as `아푸은(는)` or `진풍는` without rewriting unrelated prose.
 */
object KoreanParticleCorrector {
    data class Correction(
        val particle: String,
        val endExclusive: Int,
    )

    private enum class Pair {
        Topic,
        Subject,
        Object,
        With,
        Direction,
    }

    private val followingParticle = Regex(
        pattern = "(?:" +
            "은\\(는\\)|는\\(은\\)|은/는|는/은|" +
            "이\\(가\\)|가\\(이\\)|이/가|가/이|" +
            "을\\(를\\)|를\\(을\\)|을/를|를/을|" +
            "과\\(와\\)|와\\(과\\)|과/와|와/과|" +
            "\\(으\\)로|으로/로|로/으로|" +
            "(?:으로|은|는|이|가|을|를|과|와|로)(?![가-힣])" +
            ")",
    )

    /** Returns a corrected particle and the consumed source range, or null when no safe correction applies. */
    fun correctFollowing(
        text: String,
        startIndex: Int,
        replacedTerm: String,
    ): Correction? {
        if (startIndex !in 0..text.length) return null
        val match = followingParticle.find(text, startIndex)
            ?.takeIf { it.range.first == startIndex }
            ?: return null
        val finalSyllable = replacedTerm.lastOrNull { it in '\uAC00'..'\uD7A3' } ?: return null
        val jongseong = (finalSyllable.code - HANGUL_BASE) % HANGUL_JONGSEONG_COUNT
        val pair = match.value.toPair() ?: return null
        val particle = when (pair) {
            Pair.Topic -> if (jongseong == NO_JONGSEONG) "는" else "은"
            Pair.Subject -> if (jongseong == NO_JONGSEONG) "가" else "이"
            Pair.Object -> if (jongseong == NO_JONGSEONG) "를" else "을"
            Pair.With -> if (jongseong == NO_JONGSEONG) "와" else "과"
            Pair.Direction -> if (jongseong == NO_JONGSEONG || jongseong == RIEUL_JONGSEONG) "로" else "으로"
        }
        return Correction(particle = particle, endExclusive = match.range.last + 1)
    }

    private fun String.toPair(): Pair? = when (this) {
        "은(는)", "는(은)", "은/는", "는/은", "은", "는" -> Pair.Topic
        "이(가)", "가(이)", "이/가", "가/이", "이", "가" -> Pair.Subject
        "을(를)", "를(을)", "을/를", "를/을", "을", "를" -> Pair.Object
        "과(와)", "와(과)", "과/와", "와/과", "과", "와" -> Pair.With
        "(으)로", "으로/로", "로/으로", "으로", "로" -> Pair.Direction
        else -> null
    }

    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_JONGSEONG_COUNT = 28
    private const val NO_JONGSEONG = 0
    private const val RIEUL_JONGSEONG = 8
}
