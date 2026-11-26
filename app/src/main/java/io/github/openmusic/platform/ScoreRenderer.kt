@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class)

package io.github.openmusic.platform

import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.NotationElement
import alphaTab.PlayerMode
import alphaTab.ScrollMode
import alphaTab.Settings
import alphaTab.collections.DoubleList
import alphaTab.core.ecmaScript.Uint8Array
import alphaTab.importer.ScoreLoader
import alphaTab.model.Score
import android.graphics.Color
import android.view.ViewTreeObserver
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
                ).let { loaded ->
                    if (numbered) NumberedScoreAdapter.adapt(loaded, rendererSettings()) else loaded
                }.also { loaded ->
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
                // AlphaTabView.settings' setter immediately renders, before Compose has measured
                // the view. Mutate the shared settings object and apply it without that empty render.
                rendererSettings(view.settings)
                view.api.updateSettings()
                bridge.attach(view)
            }
        },
        update = { view -> bridge.update(view, score, positionQn) },
        onRelease = { bridge.detach(it) },
    )
}

private fun rendererSettings(settings: Settings = Settings()) = settings.apply {
    display.layoutMode = LayoutMode.Page
    // Four aligned numbered lines (melody + a left-hand triad) fit the short phone
    // landscape viewport while keeping every note and its octave dots visible.
    display.scale = 0.9
    // The app already shows score metadata above this viewport. Suppress alphaTab's
    // duplicate page header so a short landscape viewport starts with musical notation.
    // These public switches leave score.title/data and all staff/effect notation intact.
    listOf(
        NotationElement.ScoreTitle,
        NotationElement.ScoreSubTitle,
        NotationElement.ScoreArtist,
        NotationElement.ScoreAlbum,
        NotationElement.ScoreWords,
        NotationElement.ScoreMusic,
        NotationElement.ScoreWordsAndMusic,
    ).forEach { notation.elements.set(it, false) }
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
    private var pendingScore: Score? = null
    private var renderedScore: Score? = null
    private var preDrawObserver: ViewTreeObserver? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
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
        pendingScore = score
        if (score == null || score === renderedScore) {
            removePendingRender(attached)
        } else {
            awaitMeasuredContainer(attached)
        }
        position(attached)
    }

    private fun awaitMeasuredContainer(attached: AlphaTabView) {
        if (view !== attached || preDrawListener != null) return
        // AndroidView.update may run before either the outer view or alphaTab's nested scroll
        // container is measured. A pre-draw observes both after layout. Keep only one listener
        // while pending, and always read the latest score if notation changed during measurement.
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (view !== attached || pendingScore == null) {
                removePendingRender(attached)
            } else if (attached.isAttachedToWindow && attached.width > 0 && attached.height > 0 &&
                attached.api.container.width > 0.0 && attached.api.container.height > 0.0) {
                removePendingRender(attached)
                renderPending(attached)
            }
            true
        }
        preDrawListener = listener
        preDrawObserver = attached.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
        attached.postInvalidateOnAnimation()
    }

    private fun renderPending(attached: AlphaTabView) {
        val score = pendingScore ?: return
        if (view !== attached || score === renderedScore) return
        ready = false
        renderedScore = score
        lastTick = Double.NaN
        try {
            // alphaTab's -1 sentinel renders every track, including both piano staves.
            attached.api.renderScore(score, DoubleList().apply { push(-1.0) })
        } catch (error: Exception) {
            renderedScore = null
            onError("曲谱渲染失败：${error.message ?: error.javaClass.simpleName}")
        }
        // Once submitted at a nonzero size, alphaTab's own resize observer keeps handling
        // rotation and resizing. Cursor updates never resubmit the same score.
    }

    private fun removePendingRender(attached: AlphaTabView) {
        val listener = preDrawListener ?: return
        val observer = preDrawObserver
        if (observer?.isAlive == true) observer.removeOnPreDrawListener(listener)
        else if (attached.viewTreeObserver.isAlive) attached.viewTreeObserver.removeOnPreDrawListener(listener)
        preDrawListener = null
        preDrawObserver = null
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
            removePendingRender(detached)
            view = null
            pendingScore = null
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
