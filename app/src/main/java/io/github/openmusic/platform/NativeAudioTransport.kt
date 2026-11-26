package io.github.openmusic.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

data class ScheduledTone(
    val atSeconds: Double,
    val durationSeconds: Double,
    val midiPitch: Int,
    val velocity: Int = 80,
)

data class ScheduledClick(val atSeconds: Double, val accent: Boolean = false)

/** Times already include the caller's count-in, tempo map, speed and selected loop region. */
data class AudioPlan(
    val tones: List<ScheduledTone>,
    val clicks: List<ScheduledClick>,
    val durationSeconds: Double,
)

data class AudioPosition(
    val seconds: Double = 0.0,
    val timestampNanos: Long = System.nanoTime(),
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * One streaming sample clock drives the demo, click, cursor and MIDI assessment.
 * Only a short PCM buffer is allocated; a long score never becomes a whole-song PCM array.
 * The deliberately small generated instrument is a pitch reference, not a sampled piano.
 */
class NativeAudioTransport : Closeable {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OpenMusic-Audio").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(AudioPosition())
    val state: StateFlow<AudioPosition> = mutableState.asStateFlow()
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var anchor: ClockAnchor? = null
    private var activeTrack: AudioTrack? = null

    fun start(plan: AudioPlan, fromSeconds: Double = 0.0) {
        val safePlan: AudioPlan
        val startSeconds: Double
        try {
            require(plan.durationSeconds.isFinite() && plan.durationSeconds >= 0.0) { "音频片段时长无效" }
            require(fromSeconds.isFinite()) { "播放位置无效" }
            require(plan.tones.all { it.atSeconds.isFinite() && it.atSeconds >= 0.0 &&
                it.durationSeconds.isFinite() && it.durationSeconds > 0.0 &&
                it.midiPitch in 0..127 && it.velocity in 0..127 }) { "示范音事件无效" }
            require(plan.clicks.all { it.atSeconds.isFinite() && it.atSeconds >= 0.0 }) { "节拍事件无效" }
            safePlan = plan.copy(tones = plan.tones.toList(), clicks = plan.clicks.toList())
            startSeconds = fromSeconds.coerceIn(0.0, plan.durationSeconds)
        } catch (error: Exception) {
            pause()
            mutableState.value = mutableState.value.copy(error = error.message ?: "音频计划无效")
            return
        }
        val token = synchronized(gate) {
            if (closed) return
            generation++
            haltTrack()
            anchor = null
            mutableState.value = AudioPosition(startSeconds, System.nanoTime(), startSeconds < plan.durationSeconds)
            generation
        }
        if (startSeconds >= plan.durationSeconds) return
        worker.execute { stream(token, safePlan, startSeconds) }
    }

    /** Keeps the playback position. Resume by calling start(theSamePlan, state.value.seconds). */
    fun pause() {
        synchronized(gate) {
            val now = System.nanoTime()
            val pausedAt = positionAt(now) ?: mutableState.value.seconds
            generation++
            haltTrack()
            anchor = ClockAnchor(pausedAt, now, pausedAt, false)
            mutableState.value = mutableState.value.copy(seconds = pausedAt, timestampNanos = now, running = false)
        }
    }

    fun stop() {
        synchronized(gate) {
            generation++
            haltTrack()
            val now = System.nanoTime()
            anchor = ClockAnchor(0.0, now, 0.0, false)
            mutableState.value = AudioPosition(timestampNanos = now)
        }
    }

    /**
     * Maps an Android MIDI timestamp to the plan clock, including timestamps slightly before
     * the latest UI update. Null means the output has not started and has no clock anchor yet.
     */
    fun positionAt(timestampNanos: Long): Double? {
        val current = anchor ?: return null
        return if (current.running) {
            (current.seconds + (timestampNanos - current.timestampNanos) / 1_000_000_000.0)
                .coerceIn(0.0, current.durationSeconds)
        } else current.seconds
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            generation++
            haltTrack()
            anchor = null
            mutableState.value = mutableState.value.copy(running = false)
        }
        worker.shutdownNow()
    }

    private fun current(token: Long) = !closed && token == generation

    /** Caller holds gate. Release remains on the audio worker to avoid UI-thread teardown. */
    private fun haltTrack() {
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        activeTrack = null
    }

    private fun stream(token: Long, plan: AudioPlan, requestedStart: Double) {
        if (!current(token)) return
        var track: AudioTrack? = null
        try {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minBytes > 0) { "设备不支持 48 kHz 浮点 PCM 输出 ($minBytes)" }
            val bufferBytes = max(minBytes, BLOCK_FRAMES * 4 * 4)
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = output
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
            synchronized(gate) {
                if (!current(token)) return
                activeTrack = output
            }
            val startFrame = (requestedStart * SAMPLE_RATE).roundToLong()
            val startSeconds = startFrame.toDouble() / SAMPLE_RATE
            val totalFrames = ((plan.durationSeconds * SAMPLE_RATE).roundToLong() - startFrame).coerceAtLeast(0)
            if (totalFrames == 0L) {
                finish(token, plan.durationSeconds)
                return
            }
            val synth = StreamingSynth(plan, startFrame)
            val pcm = FloatArray(BLOCK_FRAMES)
            val clock = OutputClock(output, startSeconds, plan.durationSeconds)
            var writtenFrames = 0L
            var started = false
            var lastPublish = 0L
            var lastWriteProgress = System.nanoTime()
            fun publish(force: Boolean = false) {
                if (!started) return
                val now = System.nanoTime()
                if (force || now - lastPublish >= 16_000_000L) {
                    lastPublish = now
                    val observed = clock.observe(now, writtenFrames)
                    synchronized(gate) {
                        if (current(token)) {
                            anchor = observed
                            val seconds = (observed.seconds + (now - observed.timestampNanos) / 1_000_000_000.0)
                                .coerceIn(startSeconds, plan.durationSeconds)
                            mutableState.value = AudioPosition(seconds, now, true)
                        }
                    }
                }
            }
            while (current(token) && writtenFrames < totalFrames) {
                val count = min(BLOCK_FRAMES.toLong(), totalFrames - writtenFrames).toInt()
                synth.render(pcm, startFrame + writtenFrames, count)
                var offset = 0
                while (current(token) && offset < count) {
                    // Nonblocking writes make pause/seek responsive even if the audio route stalls.
                    val accepted = output.write(pcm, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(accepted >= 0) { "AudioTrack 写入失败 ($accepted)" }
                    offset += accepted
                    writtenFrames += accepted
                    if (accepted > 0) lastWriteProgress = System.nanoTime()
                    else check(System.nanoTime() - lastWriteProgress < 3_000_000_000L) {
                        "音频输出停止响应，请检查当前音频设备"
                    }
                    if (!started && writtenFrames > 0) {
                        output.play()
                        started = true
                        publish(force = true)
                    } else publish()
                    if (accepted == 0) LockSupport.parkNanos(2_000_000L)
                }
            }
            // Drain the samples actually queued, keeping the same output-clock updates.
            var lastHead = -1L
            var lastProgressNanos = System.nanoTime()
            while (current(token)) {
                val head = clock.playedFrames()
                publish()
                if (head >= totalFrames) break
                val now = System.nanoTime()
                if (head != lastHead) {
                    lastHead = head
                    lastProgressNanos = now
                }
                check(now - lastProgressNanos < 3_000_000_000L) { "音频输出停止响应，请检查当前音频设备" }
                LockSupport.parkNanos(4_000_000L)
            }
            if (current(token)) finish(token, plan.durationSeconds)
        } catch (error: Exception) {
            synchronized(gate) {
                if (current(token)) {
                    val now = System.nanoTime()
                    val seconds = positionAt(now) ?: mutableState.value.seconds
                    anchor = ClockAnchor(seconds, now, seconds, false)
                    mutableState.value = AudioPosition(seconds, now, false, "音频播放失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
        } finally {
            synchronized(gate) {
                if (activeTrack === track) activeTrack = null
            }
            track?.let { output ->
                runCatching { output.pause() }
                runCatching { output.flush() }
                runCatching { output.release() }
            }
        }
    }

    private fun finish(token: Long, duration: Double) = synchronized(gate) {
        if (current(token)) {
            val now = System.nanoTime()
            anchor = ClockAnchor(duration, now, duration, false)
            mutableState.value = AudioPosition(duration, now, false)
        }
    }

    private data class ClockAnchor(
        val seconds: Double,
        val timestampNanos: Long,
        val durationSeconds: Double,
        val running: Boolean,
    )

    /** AudioTimestamp and MIDI both use the monotonic System.nanoTime clock. */
    private class OutputClock(
        private val track: AudioTrack,
        private val fromSeconds: Double,
        private val durationSeconds: Double,
    ) {
        private val timestamp = AudioTimestamp()
        private var previousHead = 0L
        private var headWrap = 0L

        fun playedFrames(): Long {
            val raw = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (raw < previousHead) headWrap += 1L shl 32
            previousHead = raw
            return headWrap + raw
        }

        fun observe(now: Long, writtenFrames: Long): ClockAnchor {
            val validTimestamp = track.getTimestamp(timestamp) && timestamp.nanoTime > 0L &&
                abs(now - timestamp.nanoTime) < 2_000_000_000L &&
                timestamp.framePosition in 0..writtenFrames
            return if (validTimestamp) {
                ClockAnchor(fromSeconds + timestamp.framePosition.toDouble() / SAMPLE_RATE,
                    timestamp.nanoTime, durationSeconds, true)
            } else {
                // During route startup some devices have no AudioTimestamp yet. Anchor to their
                // real playback head; never substitute a timer started when the button was pressed.
                ClockAnchor(fromSeconds + min(playedFrames(), writtenFrames).toDouble() / SAMPLE_RATE,
                    now, durationSeconds, true)
            }
        }
    }

    private class StreamingSynth(plan: AudioPlan, private val initialFrame: Long) {
        private val events: List<WaveEvent> = buildList {
            plan.tones.filter { it.velocity > 0 && it.atSeconds < plan.durationSeconds }.forEach { tone ->
                add(WaveEvent(
                    (tone.atSeconds * SAMPLE_RATE).roundToLong(),
                    max(1L, (tone.durationSeconds * SAMPLE_RATE).roundToLong()),
                    440.0 * 2.0.pow((tone.midiPitch - 69) / 12.0),
                    0.16 * (tone.velocity / 127.0).pow(1.3), false,
                ))
            }
            plan.clicks.filter { it.atSeconds < plan.durationSeconds }.forEach { click ->
                add(WaveEvent((click.atSeconds * SAMPLE_RATE).roundToLong(),
                    (0.035 * SAMPLE_RATE).roundToLong(), if (click.accent) 1760.0 else 1174.66,
                    if (click.accent) 0.42 else 0.30, true))
            }
        }.sortedBy { it.startFrame }
        private var nextEvent = 0
        private val voices = ArrayList<WaveVoice>()

        fun render(target: FloatArray, firstFrame: Long, length: Int) {
            target.fill(0f, 0, length)
            val lastFrame = firstFrame + length
            while (nextEvent < events.size && events[nextEvent].startFrame < lastFrame) {
                val event = events[nextEvent++]
                if (event.endFrame > max(initialFrame, firstFrame)) {
                    // Predictable resource bounds for pathological imports; normal piano parts
                    // use far fewer simultaneous voices than this ceiling.
                    if (voices.size >= 128) voices.removeAt(0)
                    voices += WaveVoice(event)
                }
            }
            val iterator = voices.iterator()
            while (iterator.hasNext()) {
                val voice = iterator.next()
                voice.mix(target, firstFrame, length)
                if (voice.event.endFrame <= lastFrame) iterator.remove()
            }
            for (index in 0 until length) {
                val value = target[index]
                target[index] = 0.90f * value / (1.0f + abs(value))
            }
        }
    }

    private data class WaveEvent(
        val startFrame: Long,
        val durationFrames: Long,
        val frequency: Double,
        val amplitude: Double,
        val click: Boolean,
    ) {
        val releaseFrames = if (click) 240L else 2400L
        val endFrame = startFrame + durationFrames + releaseFrames
    }

    private class WaveVoice(val event: WaveEvent) {
        private val angle = 2.0 * PI * event.frequency / SAMPLE_RATE
        private val sineStep = sin(angle)
        private val cosineStep = cos(angle)
        private val decayStep = exp(-1.0 / (SAMPLE_RATE * if (event.click) 0.010 else 1.8))
        private var sine = 0.0
        private var cosine = 1.0
        private var decay = 1.0
        private var renderedUntil = Long.MIN_VALUE

        fun mix(target: FloatArray, firstFrame: Long, length: Int) {
            val first = max(firstFrame, event.startFrame)
            val last = min(firstFrame + length, event.endFrame)
            if (first >= last) return
            var age = first - event.startFrame
            if (renderedUntil != first) {
                val phase = (age * angle) % (2.0 * PI)
                sine = sin(phase)
                cosine = cos(phase)
                decay = exp(-age.toDouble() / (SAMPLE_RATE * if (event.click) 0.010 else 1.8))
            }
            for (frame in first until last) {
                val attack = min(1.0, age / 96.0)
                val release = if (age < event.durationFrames) 1.0
                    else ((event.durationFrames + event.releaseFrames - age).toDouble() / event.releaseFrames).coerceAtLeast(0.0)
                val wave = if (event.click) sine else sine + 0.28 * 2.0 * sine * cosine + 0.10 * (3.0 * sine - 4.0 * sine * sine * sine)
                val index = (frame - firstFrame).toInt()
                target[index] += (event.amplitude * wave * attack * release * decay).toFloat()
                val nextSine = sine * cosineStep + cosine * sineStep
                cosine = cosine * cosineStep - sine * sineStep
                sine = nextSine
                decay *= decayStep
                age++
            }
            renderedUntil = last
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_FRAMES = 512
    }
}
