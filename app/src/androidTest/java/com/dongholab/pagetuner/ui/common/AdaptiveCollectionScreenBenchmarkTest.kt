package com.dongholab.pagetuner.ui.common

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.FrameMetricsAggregator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendering comparison for every production screen backed by [AdaptiveCollection].
 *
 * This is deliberately an instrumentation benchmark: it measures real Compose frames on the
 * selected Android device. It is not a Microbenchmark replacement and must not be used as a hard
 * CI performance gate. Each fixture preserves the production row-height, action-count and text
 * density contract while removing network, disk and image-cache noise from the comparison.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24)
class AdaptiveCollectionScreenBenchmarkTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun benchmarkPagedAndTouchScrollForEveryAdaptiveCollectionScreen() {
        assertEquals(11, ScreenFixtures.size)

        val activeScenario = mutableStateOf(
            BenchmarkScenario(ScreenFixtures.first(), ListLayoutMode.Paged, runId = 0),
        )
        composeRule.setContent {
            val scenario = activeScenario.value
            MaterialTheme {
                CompositionLocalProvider(LocalListLayoutMode provides scenario.mode) {
                    key(scenario.runId) {
                        BenchmarkScreen(scenario.fixture)
                    }
                }
            }
        }

        val results = buildList {
            ScreenFixtures.forEach { fixture ->
                ListLayoutMode.entries.forEach { mode ->
                    repeat(WarmupTrials) { warmup ->
                        showScenario(activeScenario, fixture, mode, runId = nextRunId())
                        exercise(mode, fixture, InteractionPairs / 2)
                        Log.d(Tag, "warmup=${warmup + 1}, screen=${fixture.id}, mode=$mode")
                    }
                    repeat(MeasuredTrials) { trial ->
                        showScenario(activeScenario, fixture, mode, runId = nextRunId())
                        add(measureScenario(fixture, mode, trial + 1))
                    }
                }
            }
        }

        assertEquals(ScreenFixtures.size * ListLayoutMode.entries.size * MeasuredTrials, results.size)
        assertTrue("No measured frames were collected", results.all { it.frames > 0 })
        writeReport(results)
    }

    private fun showScenario(
        activeScenario: androidx.compose.runtime.MutableState<BenchmarkScenario>,
        fixture: ScreenFixture,
        mode: ListLayoutMode,
        runId: Int,
    ) {
        composeRule.runOnIdle {
            activeScenario.value = BenchmarkScenario(fixture, mode, runId)
        }
        composeRule.waitForIdle()
    }

    private fun measureScenario(
        fixture: ScreenFixture,
        mode: ListLayoutMode,
        trial: Int,
    ): BenchmarkResult {
        val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
        aggregator.add(composeRule.activity)
        val startedAt = SystemClock.elapsedRealtimeNanos()
        exercise(mode, fixture, InteractionPairs)
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
        val metrics = requireNotNull(aggregator.remove(composeRule.activity))
        val histogram = metrics[FrameMetricsAggregator.TOTAL_INDEX]
        val durations = buildList {
            for (index in 0 until histogram.size()) {
                repeat(histogram.valueAt(index)) { add(histogram.keyAt(index)) }
            }
        }.sorted()
        val result = BenchmarkResult(
            screenId = fixture.id,
            screenLabel = fixture.screenLabel,
            mode = mode,
            trial = trial,
            interactions = InteractionPairs * 2,
            frames = durations.size,
            jankyFrames = durations.count { it > JankThresholdMillis },
            p50Millis = durations.percentile(0.50),
            p90Millis = durations.percentile(0.90),
            p95Millis = durations.percentile(0.95),
            p99Millis = durations.percentile(0.99),
            elapsedMillis = elapsedMillis,
            pssKb = Debug.getPss(),
        )
        Log.i(Tag, result.toCsv())
        return result
    }

    private fun exercise(mode: ListLayoutMode, fixture: ScreenFixture, pairs: Int) {
        repeat(pairs) {
            when (mode) {
                ListLayoutMode.Paged -> {
                    composeRule.onNodeWithText("Next ▶").performClick()
                    composeRule.waitForIdle()
                    composeRule.onNodeWithText("◀ Prev").performClick()
                }

                ListLayoutMode.Scroll -> {
                    composeRule.onNodeWithTag(CollectionTag).performTouchInput {
                        swipeUp(durationMillis = ScrollGestureMillis)
                    }
                    composeRule.waitForIdle()
                    composeRule.onNodeWithTag(CollectionTag).performTouchInput {
                        swipeDown(durationMillis = ScrollGestureMillis)
                    }
                }
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(fixture.screenLabel).assertIsDisplayed()
    }

    private fun writeReport(results: List<BenchmarkResult>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = buildString {
            appendLine(BenchmarkResult.CsvHeader)
            results.forEach { appendLine(it.toCsv()) }
        }
        val outputs = buildList {
            add(File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), OutputFileName))
            InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir")
                ?.takeIf(String::isNotBlank)
                ?.let { outputDirectory -> add(File(outputDirectory, OutputFileName)) }
        }.distinctBy(File::getAbsolutePath)
        outputs.forEach { output ->
            output.parentFile?.mkdirs()
            output.writeText(report)
            Log.i(Tag, "result_file=${output.absolutePath}")
        }
    }

    private fun nextRunId(): Int = ++runSequence

    private companion object {
        const val Tag = "EinkScreenBenchmark"
        const val OutputFileName = "eink-screen-layout-benchmark.csv"
        const val WarmupTrials = 1
        const val MeasuredTrials = 3
        const val InteractionPairs = 10
        const val ScrollGestureMillis = 80L
        const val JankThresholdMillis = 17
        var runSequence = 0
    }
}

private data class BenchmarkScenario(
    val fixture: ScreenFixture,
    val mode: ListLayoutMode,
    val runId: Int,
)

private data class ScreenFixture(
    val id: String,
    val screenLabel: String,
    val productionPath: String,
    val pagedRowHeightDp: Int,
    val fallbackPageSize: Int,
    val itemCount: Int,
    val textLines: Int,
    val actionCount: Int,
    val hasThumbnail: Boolean = false,
    val expandedTouchRowHeightDp: Int? = null,
)

private val ScreenFixtures = listOf(
    ScreenFixture("local_library", "Local · Library", "LocalLibraryPanel", 124, 3, 48, 4, 2),
    ScreenFixture("local_directory", "Local · Device files", "LocalDirectoryBrowserPanel", 64, 5, 48, 1, 0),
    ScreenFixture("favorites", "Favorites", "FavoritesPanel", 116, 3, 48, 3, 1),
    ScreenFixture("web_catalog_page", "Web Novel · Catalog page", "WebCatalogPagePanel", 104, 3, 80, 3, 2, hasThumbnail = true, expandedTouchRowHeightDp = 132),
    ScreenFixture("web_catalog_root", "Web Novel · Root catalog", "RemoteSourcesTodoPanel/catalog", 104, 3, 80, 3, 2, hasThumbnail = true, expandedTouchRowHeightDp = 132),
    ScreenFixture("web_detail_dialog", "Web Novel · Chapter dialog", "WebNovelDetailDialog", 64, 3, 80, 1, 1),
    ScreenFixture("web_detail_chapters", "Web Novel · Book chapters", "WebNovelDetailPagePanel", 100, 3, 120, 2, 2, expandedTouchRowHeightDp = 124),
    ScreenFixture("remote_accounts", "Drive/FTP · Accounts", "RemoteSourcesTodoPanel/accounts", 112, 3, 48, 3, 2),
    ScreenFixture("book_glossary", "Reader · Book dictionary", "BookGlossaryPanel", 92, 4, 60, 2, 2),
    ScreenFixture("reader_bookmarks", "Reader · Bookmarks", "ReaderBookmarkPanel", 64, 5, 48, 2, 1),
    ScreenFixture("reader_annotations", "Reader · Notes", "ReaderAnnotationPanel", 76, 4, 48, 2, 1),
)

@Composable
private fun BenchmarkScreen(fixture: ScreenFixture) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = fixture.screenLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = EinkInk,
        )
        Text(
            text = "${fixture.productionPath} · ${fixture.itemCount} items",
            style = MaterialTheme.typography.labelSmall,
            color = EinkMuted,
        )
        AdaptiveCollection(
            items = (1..fixture.itemCount).toList(),
            estimatedPagedItemHeight = fixture.pagedRowHeightDp.dp,
            modifier = Modifier
                .weight(1f)
                .testTag(CollectionTag),
            fallbackPageSize = fixture.fallbackPageSize,
            itemKey = { it },
            scrollItemContent = fixture.expandedTouchRowHeightDp?.let { expandedHeight ->
                { item -> BenchmarkRow(fixture, item, expandedHeight) }
            },
        ) { item ->
            BenchmarkRow(fixture, item, fixture.pagedRowHeightDp)
        }
    }
}

@Composable
private fun BenchmarkRow(fixture: ScreenFixture, item: Int, heightDp: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (heightDp == fixture.pagedRowHeightDp) Modifier.height(heightDp.dp)
                else Modifier.heightIn(min = heightDp.dp),
            ),
        color = if (item % 2 == 0) EinkPanel else EinkSoft,
        border = BorderStroke(1.dp, EinkLine),
        shape = RoundedCornerShape(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fixture.hasThumbnail) {
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 54.dp),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = EinkInk,
                        shape = RoundedCornerShape(2.dp),
                    ) {}
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "$item. A deliberately long Korean/English title that exercises wrapping · 아주 긴 화면별 제목",
                    color = EinkInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = fixture.textLines.coerceAtMost(2),
                    overflow = TextOverflow.Ellipsis,
                )
                repeat((fixture.textLines - 1).coerceAtMost(3)) { line ->
                    Text(
                        text = "Metadata ${line + 1} · saved translation · EN → KO",
                        color = EinkMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            repeat(fixture.actionCount) { action ->
                TextButton(onClick = {}) {
                    Text(
                        text = if (action == 0) "Open" else "More",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private data class BenchmarkResult(
    val screenId: String,
    val screenLabel: String,
    val mode: ListLayoutMode,
    val trial: Int,
    val interactions: Int,
    val frames: Int,
    val jankyFrames: Int,
    val p50Millis: Int,
    val p90Millis: Int,
    val p95Millis: Int,
    val p99Millis: Int,
    val elapsedMillis: Long,
    val pssKb: Long,
) {
    fun toCsv(): String = listOf(
        screenId,
        csv(screenLabel),
        mode.name.lowercase(Locale.US),
        trial,
        interactions,
        frames,
        jankyFrames,
        String.format(Locale.US, "%.2f", if (frames == 0) 0.0 else jankyFrames * 100.0 / frames),
        p50Millis,
        p90Millis,
        p95Millis,
        p99Millis,
        elapsedMillis,
        pssKb,
    ).joinToString(",")

    companion object {
        const val CsvHeader = "screen_id,screen_label,mode,trial,interactions,frames,janky_frames,jank_percent,p50_ms,p90_ms,p95_ms,p99_ms,elapsed_ms,pss_kb"

        private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    }
}

private fun List<Int>.percentile(percentile: Double): Int {
    if (isEmpty()) return 0
    return this[(ceil(size * percentile).toInt() - 1).coerceIn(indices)]
}

private const val CollectionTag = "benchmark-adaptive-collection"
