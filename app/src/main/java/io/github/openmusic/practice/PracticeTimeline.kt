package io.github.openmusic.practice

import io.github.openmusic.core.CompiledScore
import io.github.openmusic.core.targetsInRange
import io.github.openmusic.platform.AudioPlan
import io.github.openmusic.platform.ScheduledClick
import io.github.openmusic.platform.ScheduledTone
import kotlin.math.max
import kotlin.math.ceil

/** One mapping serves audio, notation and evaluation. All BPM values are quarter-note BPM. */
class PracticeTimeline(
    val score: CompiledScore,
    val startIndex: Int,
    val endIndex: Int,
    bpm: Int,
    val selectedStaffIds: Set<String>,
    countInBars: Int,
) {
    init {
        require(startIndex in score.measures.indices && endIndex in startIndex..score.measures.lastIndex)
        require(bpm in 20..300 && countInBars in 0..2)
    }
    val startQn = score.measures[startIndex].startQn
    val endQn = score.measures[endIndex].endQn
    val startScoreSeconds = score.tempoMap.secondsAt(startQn)
    val endScoreSeconds = score.tempoMap.secondsAt(endQn)
    val speed = bpm / score.tempoMap.points.first().bpm
    private val firstMeasure = score.document.measures[score.measures[startIndex].sourceMeasureIndex]
    private val beatQn = 4.0 / firstMeasure.timeSignature.denominator
    private val firstBarQn = firstMeasure.timeSignature.numerator * beatQn
    private val pickupQn = if (firstMeasure.isPickup && score.measures[startIndex].durationQn < firstBarQn) score.measures[startIndex].durationQn else 0.0
    private val localQuarterSeconds = 60.0 / score.tempoMap.bpmAt(startQn) / speed
    // A pickup completes the final count-in bar: for a one-beat 4/4 pickup, count 1,2,3.
    val countInSeconds = max(0.0, countInBars * firstBarQn - if (countInBars > 0) pickupQn else 0.0) * localQuarterSeconds
    val musicSeconds = (endScoreSeconds - startScoreSeconds) / speed
    val durationSeconds = countInSeconds + musicSeconds
    val targets = targetsInRange(score, selectedStaffIds, startQn, endQn)

    fun scoreSecondsAt(planSeconds: Double): Double = startScoreSeconds + (planSeconds - countInSeconds) * speed
    fun qnAt(planSeconds: Double): Double = score.tempoMap.quarterAt(scoreSecondsAt(planSeconds)).coerceIn(startQn, endQn)
    fun planSecondsAt(scoreSeconds: Double): Double = countInSeconds + (scoreSeconds - startScoreSeconds) / speed

    fun beatAt(measureIndex: Int, quarter: Double): Double {
        val occurrence = score.measures[measureIndex]
        val measure = score.document.measures[occurrence.sourceMeasureIndex]
        val unit = 4.0 / measure.timeSignature.denominator
        val offset = if (measure.isPickup) max(0.0, measure.timeSignature.numerator * unit - occurrence.durationQn) else 0.0
        return (quarter - occurrence.startQn + offset) / unit + 1.0
    }

    fun plan(metronome: Boolean, melody: Boolean, waitingCountInOnly: Boolean = false): AudioPlan {
        val clicks = mutableListOf<ScheduledClick>()
        var countTime = 0.0
        var index = 0
        while (countTime < countInSeconds - 1e-7) {
            val beat = index % firstMeasure.timeSignature.numerator
            clicks += ScheduledClick(countTime, beat == 0 || (firstMeasure.timeSignature.denominator == 8 && firstMeasure.timeSignature.numerator % 3 == 0 && beat % 3 == 0))
            countTime += beatQn * localQuarterSeconds
            index++
        }
        if (metronome && !waitingCountInOnly) {
            for (mi in startIndex..endIndex) {
                val occurrence = score.measures[mi]
                val measure = score.document.measures[occurrence.sourceMeasureIndex]
                val unit = 4.0 / measure.timeSignature.denominator
                val offset = if (measure.isPickup) max(0.0, measure.timeSignature.numerator * unit - occurrence.durationQn) else 0.0
                var beat = ceil(offset / unit - 1e-9).toInt()
                var qn = occurrence.startQn + beat * unit - offset
                while (qn < occurrence.endQn - 1e-7) {
                    clicks += ScheduledClick(planSecondsAt(score.tempoMap.secondsAt(qn)), beat == 0 ||
                        (measure.timeSignature.denominator == 8 && measure.timeSignature.numerator % 3 == 0 && beat % 3 == 0))
                    qn += unit; beat++
                }
            }
        }
        val tones = if (melody && !waitingCountInOnly) targets.map {
            ScheduledTone(planSecondsAt(it.onsetSeconds), it.durationSeconds / speed, it.midiPitch, it.velocity)
        } else emptyList()
        return AudioPlan(tones, clicks, if (waitingCountInOnly) max(0.02, countInSeconds) else durationSeconds)
    }
}
