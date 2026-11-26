package io.github.openmusic.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A new take rearticulates notes already sounding at A; sustained tails are clipped at B. */
fun targetsInRange(
    score: CompiledScore,
    selectedStaffIds: Set<String> = emptySet(),
    startQn: Double = 0.0,
    endQn: Double = score.totalQuarterNotes,
): List<CompiledNote> {
    require(startQn.isFinite() && endQn.isFinite() && startQn >= 0 && endQn > startQn && endQn <= score.totalQuarterNotes + 1e-9)
    val firstMeasure = score.measures.indexOfLast { it.startQn <= startQn + 1e-9 }.coerceAtLeast(0)
    val clipped = score.sourceNotes.filter {
        it.endQn > startQn + 1e-9 && it.onsetQn < endQn - 1e-9 &&
            (selectedStaffIds.isEmpty() || it.staffIds.any { staff -> staff in selectedStaffIds })
    }.map { note ->
        val start = maxOf(note.onsetQn, startQn)
        val end = minOf(note.endQn, endQn)
        val seconds = score.tempoMap.secondsAt(start)
        note.copy(
            onsetQn = start, durationQn = end - start,
            onsetSeconds = seconds, durationSeconds = score.tempoMap.secondsAt(end) - seconds,
            measureOccurrenceIndex = if (note.onsetQn < startQn) firstMeasure else note.measureOccurrenceIndex,
        )
    }
    return mergeSimultaneousKeys(clipped, score.tempoMap)
}

@Serializable
enum class EvaluationKind { CORRECT, EARLY, LATE, MISSED, EXTRA }

@Serializable
data class EvaluationEvent(
    val kind: EvaluationKind,
    val noteId: String? = null,
    val midiPitch: Int,
    val timeSeconds: Double,
    val expectedSeconds: Double? = null,
    val deltaMs: Double? = null,
    val sourceIds: List<String> = emptyList(),
)

@Serializable
data class PracticeSummary(
    val expected: Int,
    val matched: Int,
    val correct: Int,
    val early: Int,
    val late: Int,
    val missed: Int,
    val extra: Int,
    val meanAbsoluteErrorMs: Double,
) {
    val noteAccuracy: Double get() = if (expected + extra == 0) 0.0 else matched.toDouble() / (expected + extra)
}

/**
 * Input time is seconds in the SAME score-time domain as CompiledScore.
 * Convert native MIDI timestamp through the session clock before calling this class.
 * timingToleranceMs and matchWindowMs are wall-clock windows, adjusted by playbackSpeed.
 */
class PracticeEvaluator(
    score: CompiledScore,
    selectedStaffIds: Set<String> = emptySet(),
    startQn: Double = 0.0,
    endQn: Double = score.totalQuarterNotes,
    private val timingToleranceMs: Double = 100.0,
    private val matchWindowMs: Double = 350.0,
    private val playbackSpeed: Double = 1.0,
) {
    init {
        require(playbackSpeed.isFinite() && playbackSpeed > 0)
        require(timingToleranceMs >= 0 && matchWindowMs >= timingToleranceMs && matchWindowMs <= 2000)
        require(startQn.isFinite() && endQn.isFinite() && endQn > startQn)
    }
    val targets: List<CompiledNote> = targetsInRange(score, selectedStaffIds, startQn, endQn)
    private val matchedIds = mutableSetOf<String>()
    private val missedIds = mutableSetOf<String>()
    private val mutableEvents = mutableListOf<EvaluationEvent>()
    private var expiryCursor = 0
    val events: List<EvaluationEvent> get() = mutableEvents.toList()

    fun onNoteOn(midiPitch: Int, timeSeconds: Double): EvaluationEvent {
        require(midiPitch in 0..127 && timeSeconds.isFinite())
        advance(timeSeconds)
        val window = matchWindowMs / 1000.0 * playbackSpeed
        val candidate = targets.asSequence().filter {
            it.midiPitch == midiPitch && it.id !in matchedIds && it.id !in missedIds && abs(it.onsetSeconds - timeSeconds) <= window + 1e-9
        }.minByOrNull { abs(it.onsetSeconds - timeSeconds) }
        val result = if (candidate == null) EvaluationEvent(EvaluationKind.EXTRA, midiPitch = midiPitch, timeSeconds = timeSeconds)
        else {
            matchedIds += candidate.id
            val delta = (timeSeconds - candidate.onsetSeconds) * 1000.0 / playbackSpeed
            val kind = when {
                delta < -timingToleranceMs -> EvaluationKind.EARLY
                delta > timingToleranceMs -> EvaluationKind.LATE
                else -> EvaluationKind.CORRECT
            }
            EvaluationEvent(kind, candidate.id, midiPitch, timeSeconds, candidate.onsetSeconds, delta, candidate.sourceIds)
        }
        mutableEvents += result
        return result
    }

    /** Finalizes missed notes only after the late matching window has closed. */
    fun advance(timeSeconds: Double): List<EvaluationEvent> {
        require(timeSeconds.isFinite())
        val added = mutableListOf<EvaluationEvent>()
        val window = matchWindowMs / 1000.0 * playbackSpeed
        while (expiryCursor < targets.size && targets[expiryCursor].onsetSeconds + window < timeSeconds - 1e-9) {
            val note = targets[expiryCursor++]
            if (note.id in matchedIds || note.id in missedIds) continue
            missedIds += note.id
            val event = EvaluationEvent(EvaluationKind.MISSED, note.id, note.midiPitch, timeSeconds, note.onsetSeconds, null, note.sourceIds)
            mutableEvents += event
            added += event
        }
        return added
    }

    /** Call only for a completed take; an interrupted take uses summary() without synthesizing misses. */
    fun finish(): PracticeSummary {
        advance((targets.lastOrNull()?.onsetSeconds ?: 0.0) + matchWindowMs / 1000.0 * playbackSpeed + 1.0)
        return summary()
    }

    fun summary(): PracticeSummary {
        val deltas = mutableEvents.mapNotNull { it.deltaMs }
        return PracticeSummary(
            expected = targets.size,
            matched = matchedIds.size,
            correct = mutableEvents.count { it.kind == EvaluationKind.CORRECT },
            early = mutableEvents.count { it.kind == EvaluationKind.EARLY },
            late = mutableEvents.count { it.kind == EvaluationKind.LATE },
            missed = missedIds.size,
            extra = mutableEvents.count { it.kind == EvaluationKind.EXTRA },
            meanAbsoluteErrorMs = if (deltas.isEmpty()) 0.0 else deltas.sumOf { abs(it) } / deltas.size,
        )
    }
}

data class WaitingResult(val accepted: Boolean, val groupCompleted: Boolean, val complete: Boolean, val currentGroupIndex: Int)

/** Waiting mode uses note-on identity and held keys, independent from fixed-tempo scoring. */
class WaitingMatcher(
    val groups: List<OnsetGroup>,
    private val chordWindowSeconds: Double = 0.8,
) {
    var index: Int = 0
        private set
    val complete: Boolean get() = index >= groups.size
    val currentGroup: OnsetGroup? get() = groups.getOrNull(index)
    private val seen = mutableSetOf<Int>()
    private val held = mutableSetOf<Int>()
    private var firstOnset: Double? = null

    fun noteOff(midiPitch: Int) { held -= midiPitch }
    fun noteOn(midiPitch: Int, monotonicSeconds: Double): WaitingResult {
        require(monotonicSeconds.isFinite())
        val group = currentGroup ?: return WaitingResult(false, false, true, index)
        held += midiPitch
        val required = group.notes.map { it.midiPitch }.toSet()
        if (midiPitch !in required) return WaitingResult(false, false, false, index)
        if (firstOnset != null && monotonicSeconds - firstOnset!! > chordWindowSeconds) { seen.clear(); firstOnset = null }
        if (firstOnset == null) firstOnset = monotonicSeconds
        seen += midiPitch
        val done = seen.containsAll(required) && held.containsAll(required)
        if (done) { index++; seen.clear(); firstOnset = null }
        return WaitingResult(true, done, complete, index)
    }
}
