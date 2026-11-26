@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic

import alphaTab.AlphaTabView
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity + native alphaTab smoke tests. No ViewModel actions or fake renderer are injected. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testName = TestName()

    @Before
    fun startInPortrait() {
        changeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        awaitText("从一个乐句开始")
    }

    @After
    fun keepFinalScreenForDiagnostics() {
        runCatching { saveScreenshot("last-${testName.methodName}") }
            .onFailure {
                if (it is ForegroundWindowFailure) throw it
                Log.w("OpenMusicSmoke", "Final screenshot unavailable", it)
            }
    }

    @Test
    fun originalScoreLoadsAndNotationSwitchesInBothOrientations() {
        openOriginalFromToday()
        val staff = awaitRenderer(numbered = false)
        assertEquals(SAMPLE_TITLE, staff.title)
        assertEquals(8, staff.measureCount)
        assertEquals(2, staff.staffCount)
        saveScreenshot("01-phone-portrait-staff")

        val numberedRender = watchRender()
        compose.onNodeWithText("简谱").performScrollTo().performClick()
        awaitRenderer(numbered = true, watch = numberedRender)
        compose.onNodeWithText("简谱").assertIsSelected()
        saveScreenshot("02-phone-portrait-numbered")

        val rotatedRender = watchRender()
        changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        awaitRenderer(numbered = true, watch = rotatedRender)
        awaitReadableFirstBar()
        compose.onNodeWithText("▶  开始").assertIsDisplayed()
        saveScreenshot("03-phone-landscape-numbered")

        val staffRender = watchRender()
        compose.onNodeWithText("五线谱").performClick()
        awaitRenderer(numbered = false, watch = staffRender)
        awaitReadableFirstBar()
        compose.onNodeWithText("五线谱").assertIsSelected()
        saveScreenshot("04-phone-landscape-staff")
    }

    @Test
    fun demonstrationAdvancesNativeCursorAndCanPause() {
        openOriginalFromToday()
        awaitRenderer(numbered = false)
        compose.onNodeWithText("示范").performScrollTo().performClick()
        compose.onNodeWithText("▶  开始").performClick()
        awaitText("Ⅱ  暂停")

        // Wait for the audio-driven cursor to leave the first beat, including the count-in.
        compose.waitUntil(TIMEOUT_MS) { rendererSnapshot()?.tick?.let { it > 0.0 } == true }
        compose.onNodeWithText("Ⅱ  暂停").performClick()
        awaitText("已暂停")
        compose.onNodeWithText("▶  开始").assertIsDisplayed()
        val paused = checkNotNull(rendererSnapshot())
        assertTrue("The actual alphaTab cursor must have advanced", paused.tick > 0.0)
        saveScreenshot("05-demonstration-paused")
    }

    @Test
    fun deviceTestKeyboardDeliversPressReleaseAndAdvancesWaitingPractice() {
        compose.onNodeWithText("设备").performClick()
        awaitText("连接你的电钢琴")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("测试键盘"))
        compose.onNodeWithContentDescription("测试键盘").performScrollTo().performClick()
        compose.onNodeWithContentDescription("测试键盘").assertIsOn()

        // A key lives inside a horizontal row within the vertical device list. Scroll the outer
        // list explicitly after expanding it, so a touch cannot land on bottom navigation.
        val key = deviceKeyInViewport("模拟琴键 C4")
        saveScreenshot("06a-device-before-key-press")
        key.performTouchInput { down(center) }
        try {
            awaitKeyState(key, "按下")
        } finally {
            key.performTouchInput { up() }
        }
        awaitKeyState(key, "松开")
        saveScreenshot("06-device-test-keyboard")

        compose.onNodeWithText("今日").performClick()
        openOriginalFromToday()
        awaitRenderer(numbered = false)
        compose.onNodeWithText("右手").performScrollTo().performClick()
        compose.onNodeWithText("弹对前进").performScrollTo().performClick()
        compose.onNodeWithText("▶  开始").performClick()
        awaitText("目标音：60")

        compose.onNodeWithContentDescription("模拟琴键 C4").performScrollTo().performTouchInput { click() }
        awaitText("目标音：62")
        assertTrue("A matched C4 must advance the real score cursor", checkNotNull(rendererSnapshot()).tick > 0.0)
        compose.onNodeWithText("Ⅱ  暂停").performClick()
        awaitText("已暂停")
        saveScreenshot("07-waiting-after-first-note")

        val rotatedRender = watchRender()
        changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        awaitRenderer(numbered = false, watch = rotatedRender)
        awaitReadableFirstBar()
        compose.onNodeWithContentDescription("模拟琴键 D4").assertIsDisplayed()
        compose.onNodeWithText("▶  开始").performClick()
        awaitText("Ⅱ  暂停")
        awaitText("目标音：62")
        compose.onNodeWithContentDescription("模拟琴键 D4").performTouchInput { click() }
        awaitText("目标音：64")
        compose.onNodeWithText("Ⅱ  暂停").performClick()
        awaitText("已暂停", substring = true)
        awaitReadableFirstBar()
        saveScreenshot("08-landscape-test-keyboard")
    }

    private fun openOriginalFromToday() {
        awaitText(SAMPLE_TITLE)
        compose.onNodeWithText("进入练习").performScrollTo().performClick()
        awaitText("▶  开始")
        compose.onNodeWithText(SAMPLE_TITLE).assertIsDisplayed()
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun deviceKeyInViewport(description: String): SemanticsNodeInteraction {
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasContentDescription(description))
        val key = compose.onNodeWithContentDescription(description)
        val viewportBefore = list.fetchSemanticsNode().boundsInRoot
        val keyBefore = key.fetchSemanticsNode().boundsInRoot
        // Center within the scroll viewport when possible. At the end of the list ScrollBy
        // clamps naturally; the bounds check below still requires the complete key to be visible.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, keyBefore.center.y - viewportBefore.center.y)
        }
        compose.waitForIdle()
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val bounds = key.fetchSemanticsNode().boundsInRoot
        Log.i("OpenMusicSmoke", "Device key $description: bounds=$bounds, listViewport=$viewport")
        key.assertIsDisplayed()
        assertTrue("The whole key must be inside the device-list viewport, above navigation",
            bounds.height > 0 && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        return key
    }

    private fun awaitKeyState(key: SemanticsNodeInteraction, state: String) {
        compose.waitUntil(TIMEOUT_MS) {
            key.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == state
        }
    }

    private fun changeOrientation(requested: Int, expected: Int) {
        compose.runOnUiThread { compose.activity.requestedOrientation = requested }
        compose.waitUntil(TIMEOUT_MS) {
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                compose.activity.resources.configuration.orientation == expected &&
                    if (expected == Configuration.ORIENTATION_LANDSCAPE) decor.width > decor.height
                    else decor.height > decor.width
            }
        }
        compose.waitForIdle()
    }

    private data class RenderWatch(val view: AlphaTabView, val completions: AtomicInteger)

    private fun watchRender(): RenderWatch = compose.runOnUiThread {
        val view = checkNotNull(findRenderer(compose.activity.window.decorView))
        val completions = AtomicInteger()
        view.api.postRenderFinished.on { completions.incrementAndGet(); Unit }
        RenderWatch(view, completions)
    }

    private fun awaitRenderer(numbered: Boolean, watch: RenderWatch? = null): RendererSnapshot {
        compose.waitUntil(TIMEOUT_MS) {
            rendererSnapshot()?.let { snapshot ->
                snapshot.title == SAMPLE_TITLE && snapshot.measureCount == 8 &&
                    (if (numbered) snapshot.staffCount >= 2 && snapshot.numberedMonophonic else snapshot.staffCount == 2) &&
                    snapshot.noteCount == 53 && snapshot.firstMeasurePitches == listOf(48, 52, 55, 60, 62, 64, 67) &&
                    (if (numbered) snapshot.numbered else snapshot.standard) && snapshot.finished && snapshot.hasBarBounds &&
                    snapshot.width > 0 && snapshot.height > 0 &&
                    (watch == null || snapshot.view !== watch.view || watch.completions.get() > 0)
            } == true
        }
        compose.waitForIdle()
        return checkNotNull(rendererSnapshot())
    }

    private fun awaitReadableFirstBar() {
        compose.waitUntil(TIMEOUT_MS) {
            rendererSnapshot()?.let { snapshot ->
                snapshot.firstBarIntersectsViewport && snapshot.firstBarVisibleRatio >= 0.95 &&
                    snapshot.nativeViewportHeight >= snapshot.firstBarHeightPixels - 2.0
            } == true
        }
    }

    private data class RendererSnapshot(
        val view: AlphaTabView,
        val rawTitle: String?,
        val title: String?,
        val hasScore: Boolean,
        val measureCount: Int,
        val staffCount: Int,
        val noteCount: Int,
        val firstMeasurePitches: List<Int>,
        val numberedMonophonic: Boolean,
        val numbered: Boolean,
        val standard: Boolean,
        val finished: Boolean,
        val hasBarBounds: Boolean,
        val width: Int,
        val height: Int,
        val rendererWidth: Double,
        val attached: Boolean,
        val shown: Boolean,
        val staffSystemCount: Int,
        val firstBarBounds: String?,
        val nativeViewportBounds: String?,
        val nativeViewportHeight: Int,
        val firstBarHeightPixels: Double,
        val firstBarIntersectsViewport: Boolean,
        val firstBarVisibleRatio: Double,
        val firstBarStaffVisibility: List<Double>,
        val tick: Double,
    )

    private fun rendererSnapshot(): RendererSnapshot? = compose.runOnUiThread {
        val view = findRenderer(compose.activity.window.decorView) ?: return@runOnUiThread null
        val score = view.api.score
        // Convert alphaTab collections before flattening to keep Kotlin List semantics.
        val staves = score?.tracks?.toList()?.flatMap { it.staves.toList() }.orEmpty()
        val beats = staves.flatMap { it.bars.toList() }.flatMap { it.voices.toList() }
            .flatMap { it.beats.toList() }
        val noteCount = beats.sumOf { it.notes.count() }
        val firstMeasurePitches = staves.flatMap { it.bars.firstOrNull()?.voices?.toList().orEmpty() }
            .flatMap { it.beats.toList() }.flatMap { it.notes.toList() }
            .map { it.realValue.toInt() }.sorted()
        val numberedMonophonic = staves.all { staff ->
            staff.bars.all { bar -> bar.voices.withIndex().all { (index, voice) ->
                voice.beats.all { beat -> beat.notes.count() <= 1 && (index == 0 || beat.notes.none()) }
            } }
        }
        val lookup = view.api.boundsLookup
        val masterBounds = lookup?.takeIf { it.isFinished }?.findMasterBarByIndex(0.0)
        val barBounds = masterBounds?.realBounds
        // alphaTab's Android render surface draws layout bounds multiplied by display density.
        // Its local visible rect includes the actual native ScrollView clipping and scroll offset.
        val surface = view.findViewById<View>(net.alphatab.R.id.renderSurface)
        val viewport = Rect()
        val hasViewport = surface?.getLocalVisibleRect(viewport) == true
        val density = view.resources.displayMetrics.density
        fun visibleFraction(rect: RectF): Double {
            val visible = RectF(rect)
            return if (hasViewport && rect.width() > 0 && rect.height() > 0 && visible.intersect(RectF(viewport))) {
                (visible.width() * visible.height() / (rect.width() * rect.height())).toDouble()
            } else 0.0
        }
        val barPixels = barBounds?.let { RectF((it.x * density).toFloat(), (it.y * density).toFloat(),
            ((it.x + it.w) * density).toFloat(), ((it.y + it.h) * density).toFloat()) }
        val intersection = barPixels?.let(::RectF)
        val intersects = hasViewport && intersection?.intersect(RectF(viewport)) == true
        val visibleRatio = if (intersects && barPixels != null && barPixels.width() > 0 && barPixels.height() > 0) {
            (intersection!!.width() * intersection.height() / (barPixels.width() * barPixels.height())).toDouble()
        } else 0.0
        val staffVisibility = masterBounds?.bars?.toList()?.sortedBy { it.visualBounds.y }?.map {
            val b = it.visualBounds
            visibleFraction(RectF((b.x * density).toFloat(), (b.y * density).toFloat(),
                ((b.x + b.w) * density).toFloat(), ((b.y + b.h) * density).toFloat()))
        }.orEmpty()
        RendererSnapshot(
            // alphaTab preserves display spacing by changing ordinary spaces to NBSP.
            view, score?.title, score?.title?.replace('\u00a0', ' '), score != null,
            score?.masterBars?.count() ?: 0, staves.size,
            noteCount, firstMeasurePitches, numberedMonophonic,
            staves.isNotEmpty() && staves.all { it.showNumbered && !it.showStandardNotation },
            staves.isNotEmpty() && staves.all { it.showStandardNotation && !it.showNumbered },
            lookup?.isFinished == true && lookup.staffSystems.any(),
            barBounds != null && barBounds.w > 0 && barBounds.h > 0,
            view.width, view.height, view.api.renderer.width, view.isAttachedToWindow, view.isShown,
            lookup?.staffSystems?.count() ?: 0,
            barBounds?.let { "x=${it.x},y=${it.y},w=${it.w},h=${it.h}" },
            if (hasViewport) viewport.toShortString() else null, if (hasViewport) viewport.height() else 0,
            barPixels?.height()?.toDouble() ?: 0.0, intersects, visibleRatio, staffVisibility,
            view.api.tickPosition,
        )
    }

    private fun findRenderer(view: View): AlphaTabView? {
        if (view is AlphaTabView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRenderer(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun saveScreenshot(name: String) {
        ensureAppForeground(name)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        ensureAppForeground(name)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        try {
            val snapshot = rendererSnapshot()
            val metadata =
                "orientation=${compose.activity.resources.configuration.orientation}\n" +
                    "image=${screenshot.width}x${screenshot.height}\n" +
                    "hasView=${snapshot != null}\nhasScore=${snapshot?.hasScore}\n" +
                    "rawTitle=${snapshot?.rawTitle?.replace("\u00a0", "\\u00a0")}\n" +
                    "title=${snapshot?.title}\nmeasures=${snapshot?.measureCount}\n" +
                    "noteCount=${snapshot?.noteCount}\nfirstMeasurePitches=${snapshot?.firstMeasurePitches}\n" +
                    "numberedMonophonic=${snapshot?.numberedMonophonic}\n" +
                    "staves=${snapshot?.staffCount}\nnumbered=${snapshot?.numbered}\nstandard=${snapshot?.standard}\n" +
                    "renderFinished=${snapshot?.finished}\nhasBarBounds=${snapshot?.hasBarBounds}\n" +
                    "staffSystems=${snapshot?.staffSystemCount}\nfirstBarBounds=${snapshot?.firstBarBounds}\n" +
                    "nativeViewport=${snapshot?.nativeViewportBounds}\nnativeViewportHeight=${snapshot?.nativeViewportHeight}\n" +
                    "firstBarHeightPixels=${snapshot?.firstBarHeightPixels}\nfirstBarIntersectsViewport=${snapshot?.firstBarIntersectsViewport}\n" +
                    "firstBarVisibleRatio=${snapshot?.firstBarVisibleRatio}\nfirstBarStaffVisibility=${snapshot?.firstBarStaffVisibility}\n" +
                    "view=${snapshot?.width}x${snapshot?.height}\nrendererWidth=${snapshot?.rendererWidth}\n" +
                    "attached=${snapshot?.attached}\nshown=${snapshot?.shown}\ntick=${snapshot?.tick}\n"
            Log.i("OpenMusicSmoke", "Snapshot $name:\n$metadata")
            // AGP/UTP copies this directory before uninstalling the APK. App external files are
            // only the fallback for manual instrumentation without an additional-output argument.
            val directory = screenshotDirectory()
            val output = File(directory, "$name.png")
            output.outputStream().use { stream -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            File(directory, "$name.txt").writeText(metadata)
            Log.i("OpenMusicSmoke", "Screenshot: ${output.absolutePath}")
        } finally {
            screenshot.recycle()
        }
    }

    private class ForegroundWindowFailure(message: String, cause: Throwable) : AssertionError(message, cause)

    /** Only the observed, explicitly named system Pixel Launcher ANR is recoverable here. */
    @Suppress("DEPRECATION") // recycle remains required on older supported Android versions.
    private fun ensureAppForeground(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        var dismissedLauncher = false
        var description = "No active accessibility window"
        try {
            compose.waitUntil(10_000L) {
                val windows = automation.windows
                val root = try {
                    (windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive })?.root
                        ?: automation.rootInActiveWindow
                } finally {
                    windows.forEach { it.recycle() }
                }
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                if (root != null) nodes += root
                try {
                    var index = 0
                    while (index < nodes.size && nodes.size < 512) {
                        val node = nodes[index++]
                        for (child in 0 until node.childCount) node.getChild(child)?.let { nodes += it }
                    }
                    val packageName = root?.packageName?.toString()
                    val texts = nodes.mapNotNull { it.text?.toString() }
                        .map { it.replace('\u00a0', ' ').replace('’', '\'').trim() }
                    description = "package=$packageName; window=${root?.windowId}; texts=${texts.joinToString(" | ")}"
                    when {
                        root == null -> false
                        packageName == "io.github.openmusic" -> true
                        packageName in setOf("android", "com.android.systemui") &&
                            texts.any { it == "Pixel Launcher isn't responding" } -> {
                            if (!dismissedLauncher) {
                                saveRawScreenshot("$name-external-pixel-launcher", description)
                                var close = nodes.firstOrNull { it.text?.toString()?.trim() == "Close app" && it.isEnabled }
                                var parentDepth = 0
                                while (close != null && !close.isClickable && parentDepth++ < 4) {
                                    close = close.parent?.also { nodes += it }
                                }
                                check(close != null && close.windowId == root.windowId && close.isVisibleToUser &&
                                    close.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    "Cannot activate the specific Pixel Launcher Close app action"
                                }
                                dismissedLauncher = true
                                Log.w("OpenMusicSmoke", "Dismissed external Pixel Launcher ANR only: $description")
                            }
                            false
                        }
                        else -> error("Unexpected foreground window; no action taken: $description")
                    }
                } finally {
                    nodes.forEach { it.recycle() }
                }
            }
        } catch (error: Throwable) {
            runCatching { saveRawScreenshot("$name-blocked-foreground", description) }
                .onFailure { Log.e("OpenMusicSmoke", "Failed to save original foreground screenshot", it) }
            throw ForegroundWindowFailure("OpenMusic must own the foreground before capture. $description", error)
        }
    }

    /** Takes an unmodified system screenshot without waiting on the app's UI thread. */
    private fun saveRawScreenshot(name: String, details: String) {
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = screenshotDirectory()
            File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(directory, "$name.txt").writeText(details)
            Log.w("OpenMusicSmoke", "Original foreground screenshot $name: $details")
        } finally {
            screenshot.recycle()
        }
    }

    private fun screenshotDirectory(): File {
        val additionalOutput = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
        val directory = if (additionalOutput != null) File(additionalOutput)
            else checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots"))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot output: $directory" }
        return directory
    }

    companion object {
        private const val SAMPLE_TITLE = "四拍入门 · 原创双手练习"
        private const val TIMEOUT_MS = 30_000L
    }
}
