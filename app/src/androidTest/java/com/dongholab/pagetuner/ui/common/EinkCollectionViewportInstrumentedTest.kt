package com.dongholab.pagetuner.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Compose bounds for fixed rows, navigation, scaled fonts, and asynchronous list refresh. */
@RunWith(AndroidJUnit4::class)
class EinkCollectionViewportInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rowAndNavigationFitAtBoundaryAndDisappearTogetherBelowIt() {
        val height = mutableStateOf(170.dp)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AdaptiveCollection(
                        items = (0 until 30).toList(), estimatedPagedItemHeight = 104.dp,
                        modifier = Modifier.height(height.value).testTag("viewport"), itemKey = { it },
                    ) { Text("Item $it", Modifier.fillMaxWidth().height(104.dp).testTag("row-$it")) }
                }
            }
        }
        composeRule.onNodeWithTag("row-0").assertIsDisplayed().assertHeightIsEqualTo(104.dp)
        composeRule.onNodeWithText("Next ▶").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val row = composeRule.onNodeWithTag("row-0").fetchSemanticsNode().boundsInRoot
        assertTrue(row.top >= viewport.top && row.bottom <= viewport.bottom)
        composeRule.runOnIdle { height.value = 169.dp }
        composeRule.onNodeWithTag("row-0").assertDoesNotExist()
        composeRule.onNodeWithText("Next ▶").assertDoesNotExist()
    }

    @Test
    fun resizeAndPrependingARefreshedListKeepTheAnchoredItemVisible() {
        val height = mutableStateOf(280.dp)
        val items = mutableStateOf((0 until 30).toList())
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AdaptiveCollection(
                        items = items.value, estimatedPagedItemHeight = 104.dp,
                        modifier = Modifier.height(height.value), itemKey = { it },
                    ) { Text("Item $it", Modifier.fillMaxWidth().height(104.dp).testTag("row-$it")) }
                }
            }
        }
        composeRule.onNodeWithText("Next ▶").performClick()
        composeRule.onNodeWithTag("row-2").assertIsDisplayed()
        composeRule.runOnIdle { height.value = 170.dp }
        composeRule.onNodeWithTag("row-2").assertIsDisplayed()
        composeRule.runOnIdle { items.value = listOf(-2, -1) + items.value }
        composeRule.onNodeWithTag("row-2").assertIsDisplayed()
        composeRule.runOnIdle { height.value = 280.dp }
        composeRule.onNodeWithTag("row-2").assertIsDisplayed()
    }

    @Test
    fun largeFontNavigationAndRowUseTheSameMeasuredHeightBudget() {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 3f)) {
                    val navHeight = einkPageNavigationHeight()
                    Box(Modifier.fillMaxSize()) {
                        AdaptiveCollection(
                            items = (0 until 30).toList(), estimatedPagedItemHeight = 104.dp,
                            modifier = Modifier.height(navHeight + 6.dp + 104.dp).testTag("viewport"),
                            itemKey = { it },
                        ) { Text("$it", Modifier.fillMaxWidth().height(104.dp).testTag("row-$it")) }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("row-0").assertIsDisplayed()
        composeRule.onNodeWithTag("row-1").assertDoesNotExist()
        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val row = composeRule.onNodeWithTag("row-0").fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText("Next ▶").fetchSemanticsNode().boundsInRoot
        assertTrue(next.height >= 44f)
        assertTrue(next.top >= viewport.top && next.bottom <= row.top)
        assertTrue(row.top >= viewport.top && row.bottom <= viewport.bottom)
    }
}
