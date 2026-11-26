package io.github.openmusic.practice

import io.github.openmusic.core.*
import org.junit.Assert.*
import org.junit.Test

class PracticeTimelineTest {
    private fun note(id: String, onset: String = "0/1", duration: String = "1/1") = ScoreEvent(
        id, partId = "piano", staffId = "RH", onsetQn = onset, durationQn = duration,
        pitch = Pitch("C", octave = 4, midi = 60),
    )
    private fun compile(vararg measures: ScoreMeasure, changes: List<TempoChange> = emptyList()) = ScoreCompiler.compile(
        ScoreDocument(scoreId = "timeline", metadata = ScoreMetadata("Timeline"),
            parts = listOf(ScorePart("piano", staves = listOf(ScoreStaff("RH")))),
            measures = measures.toList(), playbackStructure = PlaybackStructure(tempoChanges = changes)),
    )

    @Test fun tempoChangesKeepAudioCursorAndSelectedRangeInOneDomain() {
        val score = compile(
            ScoreMeasure("a", 1, tempoBpm = 60.0, events = listOf(note("a-note", duration = "4/1"))),
            ScoreMeasure("b", 2, tempoBpm = 120.0, events = listOf(note("b-note", duration = "4/1"))),
        )
        val timeline = PracticeTimeline(score, 1, 1, 120, setOf("RH"), 1)
        // Global 2x speed: selected bar's 120 BPM becomes 240 BPM, one bar is one second.
        assertEquals(1.0, timeline.countInSeconds, 1e-9)
        assertEquals(1.0, timeline.musicSeconds, 1e-9)
        assertEquals(4.0, timeline.qnAt(1.0), 1e-9)
        assertEquals(6.0, timeline.qnAt(1.5), 1e-9)
        val tone = timeline.plan(true, true).tones.single()
        assertEquals(1.0, tone.atSeconds, 1e-9)
        assertEquals(1.0, tone.durationSeconds, 1e-9)
        assertEquals(5.0, timeline.scoreSecondsAt(1.5), 1e-9)
    }

    @Test fun aSustainedNoteUsesBothTemposInsideTheBar() {
        val score = compile(ScoreMeasure("m", 1, tempoBpm = 60.0, events = listOf(note("n", duration = "4/1"))),
            changes = listOf(TempoChange("m", "2/1", 120.0)))
        val t = PracticeTimeline(score, 0, 0, 60, setOf("RH"), 0)
        assertEquals(3.0, t.musicSeconds, 1e-9)
        assertEquals(listOf(0.0, 1.0, 2.0, 2.5), t.plan(true, true).clicks.map { it.atSeconds })
        assertEquals(3.0, t.plan(true, true).tones.single().durationSeconds, 1e-9)
        assertEquals(3.0, t.qnAt(2.5), 1e-9)
    }

    @Test fun quarterPickupFinishesTheCountInBarWithoutAFalseDownbeat() {
        val score = compile(
            ScoreMeasure("pickup", 1, tempoBpm = 60.0, isPickup = true, durationQn = "1/1", events = listOf(note("pick"))),
            ScoreMeasure("full", 2, events = listOf(note("full-note"))),
        )
        val t = PracticeTimeline(score, 0, 1, 60, setOf("RH"), 1)
        assertEquals(3.0, t.countInSeconds, 1e-9)
        assertEquals(4.0, t.beatAt(0, 0.0), 1e-9)
        val clicks = t.plan(true, true).clicks
        assertFalse(clicks.single { it.atSeconds == 3.0 }.accent)
        assertTrue(clicks.single { it.atSeconds == 4.0 }.accent)
        assertEquals(3.0, t.plan(true, true).tones.first().atSeconds, 1e-9)
    }

    @Test fun eighthPickupFallsBetweenClicksOnTheMeterGrid() {
        val score = compile(
            ScoreMeasure("pickup", 1, tempoBpm = 60.0, isPickup = true, durationQn = "1/2", events = listOf(note("p", duration = "1/2"))),
            ScoreMeasure("full", 2, events = listOf(note("f"))),
        )
        val t = PracticeTimeline(score, 0, 1, 60, setOf("RH"), 1)
        assertEquals(3.5, t.countInSeconds, 1e-9)
        assertFalse(t.plan(true, true).clicks.any { it.atSeconds == 3.5 })
        assertEquals(3.5, t.plan(true, true).tones.first().atSeconds, 1e-9)
        assertTrue(t.plan(true, true).clicks.single { it.atSeconds == 4.0 }.accent)
    }

    @Test fun sixEightUsesEighthNotePulsesAndThreePlusThreeAccents() {
        val score = compile(ScoreMeasure("m", 1, timeSignature = TimeSignature(6, 8), tempoBpm = 60.0,
            events = listOf(note("n", duration = "3/1"))))
        val t = PracticeTimeline(score, 0, 0, 60, setOf("RH"), 1)
        assertEquals(3.0, t.countInSeconds, 1e-9)
        assertEquals(listOf(0.0, 1.5, 3.0, 4.5), t.plan(true, false).clicks.filter { it.accent }.map { it.atSeconds })
        assertEquals(12, t.plan(true, false).clicks.size)
    }

    @Test fun waitPlanStopsAfterTheCountInAndNoCountInStillProducesAClock() {
        val score = compile(ScoreMeasure("m", 1, tempoBpm = 60.0, events = listOf(note("n"))))
        val t = PracticeTimeline(score, 0, 0, 60, setOf("RH"), 1)
        val wait = t.plan(metronome = false, melody = true, waitingCountInOnly = true)
        assertEquals(4.0, wait.durationSeconds, 1e-9)
        assertEquals(4, wait.clicks.size)
        assertTrue(wait.tones.isEmpty())
        val silent = PracticeTimeline(score, 0, 0, 60, setOf("RH"), 0).plan(false, false, true)
        assertEquals(0.02, silent.durationSeconds, 1e-9)
        assertTrue(silent.clicks.isEmpty())
    }
}
