@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.ScrollMode
import alphaTab.Settings
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline native engraving. The alphaTab player stays paused in external-media mode:
 * tickPosition is driven exclusively by the app's AudioTrack clock, including interpolation.
 * No WebView, JavaScript bridge, network resource or additional soundfont download is used.
 */
@Composable
fun ScoreRenderer(
    musicXml: String,
    numbered: Boolean,
    positionQn: Double,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
) {
    val latestError by rememberUpdatedState(onError)
    val score by produceState<Score?>(null, musicXml, numbered) {
        value = null
        if (musicXml.isBlank()) return@produceState
        try {
            value = withContext(Dispatchers.Default) {
                ScoreLoader.loadScoreFromBytes(
                    Uint8Array(musicXml.toByteArray(Charsets.UTF_8).asUByteArray()), rendererSettings(),
                ).also { loaded ->
                    loaded.tracks.forEach { track ->
                        track.staves.forEach { staff ->
                            staff.showStandardNotation = !numbered
                            staff.showNumbered = numbered
                            staff.showTablature = false
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            latestError("曲谱显示失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
    val bridge = remember { RendererBridge() }
    bridge.onError = { latestError(it) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AlphaTabView(context, null).also { view ->
                view.setBackgroundColor(Color.WHITE)
                view.barCursorFillColor = Color.argb(24, 21, 129, 122)
                view.beatCursorFillColor = Color.rgb(21, 129, 122)
                view.settings = rendererSettings()
                view.api.updateSettings()
                bridge.attach(view)
            }
        },
        update = { view -> bridge.update(view, score, positionQn) },
        onRelease = { bridge.detach(it) },
    )
}

private fun rendererSettings() = Settings().apply {
    display.layoutMode = LayoutMode.Page
    display.scale = 1.0
    player.playerMode = PlayerMode.EnabledExternalMedia
    player.enableCursor = true
    // While paused this option interpolates immediately to the supplied tick; it starts no timer.
    player.enableAnimatedBeatCursor = true
    player.enableUserInteraction = false
    player.scrollMode = ScrollMode.OffScreen
}

private class RendererBridge {
    var onError: (String) -> Unit = {}
    private var view: AlphaTabView? = null
    private var renderedScore: Score? = null
    private var positionQn = 0.0
    private var lastTick = Double.NaN
    private var ready = false
    private var scrollPosted = false

    fun attach(attached: AlphaTabView) {
        view = attached
        attached.api.error.on { error ->
            onError("曲谱渲染失败：${error.message ?: error.javaClass.simpleName}")
        }
        attached.api.postRenderFinished.on {
            if (view === attached) {
                ready = true
                position(attached, force = true)
            }
        }
        attached.api.midiLoaded.on {
            if (view === attached) position(attached, force = true)
        }
    }

    fun update(attached: AlphaTabView, score: Score?, newPositionQn: Double) {
        positionQn = if (newPositionQn.isFinite()) newPositionQn.coerceAtLeast(0.0) else 0.0
        if (score != null && renderedScore !== score) {
            ready = false
            renderedScore = score
            lastTick = Double.NaN
            try {
                // alphaTab's -1 sentinel renders every track, including both piano staves.
                attached.api.renderScore(score, DoubleList().apply { push(-1.0) })
            } catch (error: Exception) {
                onError("曲谱渲染失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
        position(attached)
    }

    private fun position(attached: AlphaTabView, force: Boolean = false) {
        if (!ready || view !== attached) return
        // alphaTab 1.6.1 uses 960 ticks per quarter (midi/MidiUtils.kt, QuarterTime).
        // MidiUtils is internal in its Android artifact, so keep the pinned format value here.
        val tick = positionQn * ALPHATAB_TICKS_PER_QUARTER
        if (!force && tick == lastTick) return
        lastTick = tick
        try {
            attached.api.tickPosition = tick
            // alphaTab updates cursor bounds through its main-thread facade. Queue the scroll
            // after those bounds, coalescing updates when audio state arrives faster than frames.
            if (!scrollPosted) {
                scrollPosted = true
                attached.post {
                    scrollPosted = false
                    if (view === attached) attached.api.scrollToCursor()
                }
            }
        } catch (error: Exception) {
            onError("游标更新失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun detach(detached: AlphaTabView) {
        if (view === detached) {
            view = null
            renderedScore = null
            ready = false
            scrollPosted = false
        }
        // AlphaTabView.onDetachedFromWindow() owns api.destroy() and its worker cleanup.
    }

    companion object {
        private const val ALPHATAB_TICKS_PER_QUARTER = 960.0
    }
}
