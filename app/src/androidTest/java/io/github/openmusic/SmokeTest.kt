@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic

import alphaTab.AlphaTabView
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
            .onFailure { Log.w("OpenMusicSmoke", "Final screenshot unavailable", it) }
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
        compose.onNodeWithText("▶  开始").assertIsDisplayed()
        saveScreenshot("03-phone-landscape-numbered")

        val staffRender = watchRender()
        compose.onNodeWithText("五线谱").performScrollTo().performClick()
        awaitRenderer(numbered = false, watch = staffRender)
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
    }

    private fun openOriginalFromToday() {
        awaitText(SAMPLE_TITLE)
        compose.onNodeWithText("进入练习").performScrollTo().performClick()
        awaitText("▶  开始")
        compose.onNodeWithText(SAMPLE_TITLE).assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
                snapshot.title == SAMPLE_TITLE && snapshot.measureCount == 8 && snapshot.staffCount == 2 &&
                    (if (numbered) snapshot.numbered else snapshot.standard) && snapshot.finished && snapshot.hasBarBounds &&
                    snapshot.width > 0 && snapshot.height > 0 &&
                    (watch == null || snapshot.view !== watch.view || watch.completions.get() > 0)
            } == true
        }
        compose.waitForIdle()
        return checkNotNull(rendererSnapshot())
    }

    private data class RendererSnapshot(
        val view: AlphaTabView,
        val rawTitle: String?,
        val title: String?,
        val hasScore: Boolean,
        val measureCount: Int,
        val staffCount: Int,
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
        val tick: Double,
    )

    private fun rendererSnapshot(): RendererSnapshot? = compose.runOnUiThread {
        val view = findRenderer(compose.activity.window.decorView) ?: return@runOnUiThread null
        val score = view.api.score
        val staves = score?.tracks?.flatMap { it.staves }.orEmpty()
        val lookup = view.api.boundsLookup
        val barBounds = lookup?.takeIf { it.isFinished }?.findMasterBarByIndex(0.0)?.realBounds
        RendererSnapshot(
            // alphaTab preserves display spacing by changing ordinary spaces to NBSP.
            view, score?.title, score?.title?.replace('\u00a0', ' '), score != null,
            score?.masterBars?.count() ?: 0, staves.size,
            staves.isNotEmpty() && staves.all { it.showNumbered && !it.showStandardNotation },
            staves.isNotEmpty() && staves.all { it.showStandardNotation && !it.showNumbered },
            lookup?.isFinished == true && lookup.staffSystems.any(),
            barBounds != null && barBounds.w > 0 && barBounds.h > 0,
            view.width, view.height, view.api.renderer.width, view.isAttachedToWindow, view.isShown,
            lookup?.staffSystems?.count() ?: 0,
            barBounds?.let { "x=${it.x},y=${it.y},w=${it.w},h=${it.h}" },
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
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        try {
            val snapshot = rendererSnapshot()
            val metadata =
                "orientation=${compose.activity.resources.configuration.orientation}\n" +
                    "image=${screenshot.width}x${screenshot.height}\n" +
                    "hasView=${snapshot != null}\nhasScore=${snapshot?.hasScore}\n" +
                    "rawTitle=${snapshot?.rawTitle?.replace("\u00a0", "\\u00a0")}\n" +
                    "title=${snapshot?.title}\nmeasures=${snapshot?.measureCount}\n" +
                    "staves=${snapshot?.staffCount}\nnumbered=${snapshot?.numbered}\nstandard=${snapshot?.standard}\n" +
                    "renderFinished=${snapshot?.finished}\nhasBarBounds=${snapshot?.hasBarBounds}\n" +
                    "staffSystems=${snapshot?.staffSystemCount}\nfirstBarBounds=${snapshot?.firstBarBounds}\n" +
                    "view=${snapshot?.width}x${snapshot?.height}\nrendererWidth=${snapshot?.rendererWidth}\n" +
                    "attached=${snapshot?.attached}\nshown=${snapshot?.shown}\ntick=${snapshot?.tick}\n"
            Log.i("OpenMusicSmoke", "Snapshot $name:\n$metadata")
            // AGP/UTP copies this directory before uninstalling the APK. App external files are
            // only the fallback for manual instrumentation without an additional-output argument.
            val additionalOutput = InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }
            val directory = if (additionalOutput != null) File(additionalOutput)
                else checkNotNull(compose.activity.getExternalFilesDir("screenshots"))
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create screenshot output: $directory" }
            val output = File(directory, "$name.png")
            output.outputStream().use { stream -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            File(directory, "$name.txt").writeText(metadata)
            Log.i("OpenMusicSmoke", "Screenshot: ${output.absolutePath}")
        } finally {
            screenshot.recycle()
        }
    }

    companion object {
        private const val SAMPLE_TITLE = "四拍入门 · 原创双手练习"
        private const val TIMEOUT_MS = 30_000L
    }
}
