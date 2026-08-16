package com.dongholab.pagetuner.ui.reader

import androidx.compose.ui.text.font.FontWeight
import com.dongholab.pagetuner.translation.glossary.GlossaryDisplayText
import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryAnnotatedTextTest {
    @Test
    fun characterAliasRangeBecomesBoldAnnotatedText() {
        val annotated = GlossaryDisplayText(
            text = "아푸가 문을 열었다.",
            emphasizedRanges = listOf(0..1),
        ).toEmphasizedAnnotatedString()

        assertEquals("아푸가 문을 열었다.", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles.single().start)
        assertEquals(2, annotated.spanStyles.single().end)
        assertEquals(FontWeight.Bold, annotated.spanStyles.single().item.fontWeight)
    }
}
